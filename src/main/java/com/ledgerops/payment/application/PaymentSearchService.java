package com.ledgerops.payment.application;

import com.ledgerops.identity.api.AuthorizationPermissionDeniedException;
import com.ledgerops.identity.api.AuthorizationResourceNotFoundException;
import com.ledgerops.identity.api.AuthorizedRequestContext;
import com.ledgerops.payment.api.PaymentSearchItem;
import com.ledgerops.payment.api.PaymentSearchPage;
import com.ledgerops.payment.api.PaymentSearchPort;
import com.ledgerops.payment.api.PaymentSearchQuery;
import com.ledgerops.payment.domain.PaymentStatus;
import com.ledgerops.risk.api.RiskPaymentQuery;
import com.ledgerops.risk.api.RiskPaymentSnapshot;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
class PaymentSearchService implements PaymentSearchPort {

    private static final int RAW_BATCH_SIZE = 101;

    private final PaymentSearchStore store;
    private final RiskPaymentQuery risk;

    PaymentSearchService(PaymentSearchStore store, RiskPaymentQuery risk) {
        this.store = Objects.requireNonNull(store, "Payment search store must not be null");
        this.risk = Objects.requireNonNull(risk, "Risk Payment query must not be null");
    }

    @Override
    @Transactional(readOnly = true)
    public PaymentSearchPage findPage(PaymentSearchQuery query) {
        Objects.requireNonNull(query, "Payment search query must not be null");
        requireAccess(query);

        String fingerprint = PaymentSearchFingerprint.of(query);
        PaymentPageCursor cursor = query.cursor() == null
                ? null
                : PaymentPageCursorCodec.decode(query.cursor());
        if (cursor != null && (cursor.version() != 1
                || !fingerprint.equals(cursor.queryFingerprint()))) {
            throw new InvalidPaymentCursorException();
        }

        List<PaymentSearchItem> matches = new ArrayList<>();
        Instant rawCreatedAt = cursor == null ? null : cursor.createdAt();
        UUID rawPaymentId = cursor == null ? null : cursor.paymentId();
        boolean sourceHasMore;
        do {
            PaymentSearchStore.Batch batch = store.findBatch(
                    query, rawCreatedAt, rawPaymentId, RAW_BATCH_SIZE);
            sourceHasMore = batch.hasMore();
            for (PaymentSearchStore.Row row : batch.rows()) {
                rawCreatedAt = row.createdAt();
                rawPaymentId = row.paymentId();
                PaymentSearchItem item = enrich(row, query.tenantId());
                if (matches(query, item)) {
                    matches.add(item);
                    if (matches.size() == query.limit() + 1) {
                        break;
                    }
                }
            }
            if (matches.size() == query.limit() + 1) {
                break;
            }
            if (batch.rows().isEmpty()) {
                sourceHasMore = false;
            }
        } while (sourceHasMore);

        boolean hasNext = matches.size() > query.limit();
        if (hasNext) {
            matches.remove(matches.size() - 1);
        }
        String nextCursor = hasNext && !matches.isEmpty()
                ? PaymentPageCursorCodec.encode(new PaymentPageCursor(
                1,
                matches.getLast().createdAt(),
                matches.getLast().paymentId(),
                fingerprint))
                : null;
        return new PaymentSearchPage(matches, nextCursor);
    }

    private void requireAccess(PaymentSearchQuery query) {
        AuthorizedRequestContext authorization = query.authorization();
        if (!authorization.isHuman() || !authorization.canReadPayments()) {
            throw new AuthorizationPermissionDeniedException("payment:read");
        }
        if (!authorization.tenantId().equals(query.tenantId())) {
            throw new AuthorizationResourceNotFoundException();
        }
        if (query.merchantReference() != null
                && !authorization.allowsMerchant(query.merchantReference())) {
            throw new AuthorizationResourceNotFoundException();
        }
    }

    private PaymentSearchItem enrich(PaymentSearchStore.Row row, UUID tenantId) {
        Optional<RiskPaymentSnapshot> riskSnapshot = risk.findSnapshotByTenantAndPayment(
                tenantId, row.paymentId());
        return new PaymentSearchItem(
                row.paymentId(),
                row.tenantId(),
                row.merchantReference(),
                row.customerId(),
                row.amount(),
                row.currency(),
                row.state().name(),
                row.createdAt(),
                row.updatedAt(),
                riskSnapshot.map(RiskPaymentSnapshot::decision).orElse(null),
                reconciliationStatus(row.state()));
    }

    private boolean matches(PaymentSearchQuery query, PaymentSearchItem item) {
        return (query.riskDecision() == null || query.riskDecision() == item.riskDecision())
                && (query.reconciliationStatus() == null
                || query.reconciliationStatus().equals(item.reconciliationStatus()));
    }

    private String reconciliationStatus(PaymentStatus status) {
        return switch (status) {
            case COMPLETED, REVERSED -> "AWAITING_BATCH";
            default -> "NOT_APPLICABLE";
        };
    }
}
