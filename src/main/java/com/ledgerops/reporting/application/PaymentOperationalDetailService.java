package com.ledgerops.reporting.application;

import com.ledgerops.identity.api.AuthorizationPermissionDeniedException;
import com.ledgerops.identity.api.AuthorizationResourceNotFoundException;
import com.ledgerops.identity.api.AuthorizedRequestContext;
import com.ledgerops.ledger.api.PaymentSuccessLedger;
import com.ledgerops.payment.api.PaymentDetailsQuery;
import com.ledgerops.payment.api.PaymentDetailsSnapshot;
import com.ledgerops.payment.api.ReversalDetailsQuery;
import com.ledgerops.provider.api.ProviderPaymentEvidenceQuery;
import com.ledgerops.provider.api.ProviderPaymentOperationsQuery;
import com.ledgerops.reporting.api.PaymentOperationalDetail;
import com.ledgerops.reporting.api.PaymentTimelineEntry;
import com.ledgerops.reporting.api.PaymentTimelineQuery;
import com.ledgerops.risk.api.RiskPaymentQuery;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class PaymentOperationalDetailService {

    private final PaymentDetailsQuery payments;
    private final ReversalDetailsQuery reversals;
    private final RiskPaymentQuery risk;
    private final ProviderPaymentEvidenceQuery provider;
    private final ProviderPaymentOperationsQuery providerOperations;
    private final PaymentSuccessLedger ledger;
    private final PaymentTimelineQuery timeline;
    private final PaymentTimelineProjector projector;

    public PaymentOperationalDetailService(
            PaymentDetailsQuery payments,
            ReversalDetailsQuery reversals,
            RiskPaymentQuery risk,
            ProviderPaymentEvidenceQuery provider,
            ProviderPaymentOperationsQuery providerOperations,
            PaymentSuccessLedger ledger,
            PaymentTimelineQuery timeline,
            PaymentTimelineProjector projector
    ) {
        this.payments = payments;
        this.reversals = reversals;
        this.risk = risk;
        this.provider = provider;
        this.providerOperations = providerOperations;
        this.ledger = ledger;
        this.timeline = timeline;
        this.projector = projector;
    }

    @Transactional
    public PaymentOperationalDetail find(
            UUID tenantId,
            UUID paymentId,
            AuthorizedRequestContext authorization
    ) {
        requireAccess(tenantId, authorization);
        PaymentDetailsSnapshot payment = payments.findByTenantAndPayment(tenantId, paymentId)
                .orElseThrow(AuthorizationResourceNotFoundException::new);
        if (!authorization.allowsMerchant(payment.merchantId())) {
            throw new AuthorizationResourceNotFoundException();
        }
        projector.ensureBaseline(payment);
        List<PaymentTimelineEntry> paymentTimeline = timeline.findByTenantAndPayment(
                tenantId, paymentId);
        return new PaymentOperationalDetail(
                payment,
                risk.findSnapshotByTenantAndPayment(tenantId, paymentId).orElse(null),
                provider.findByTenantAndPayment(tenantId, paymentId),
                ledger.findByPaymentSource(tenantId, paymentId).orElse(null),
                reconciliationStatus(payment),
                paymentTimeline,
                payment.notes(),
                payment.attempts(),
                providerOperations.findByTenantAndPayment(tenantId, paymentId).orElse(null),
                reversals.findByTenantAndPayment(tenantId, paymentId).orElse(null));
    }

    private void requireAccess(UUID tenantId, AuthorizedRequestContext authorization) {
        Objects.requireNonNull(authorization, "Authorization context must not be null");
        if (!authorization.isHuman() || !authorization.canReadPayments()) {
            throw new AuthorizationPermissionDeniedException("payment:read");
        }
        if (!authorization.tenantId().equals(tenantId)) {
            throw new AuthorizationResourceNotFoundException();
        }
    }

    private String reconciliationStatus(PaymentDetailsSnapshot payment) {
        return switch (payment.state()) {
            case "COMPLETED", "REVERSED" -> "AWAITING_BATCH";
            default -> "NOT_APPLICABLE";
        };
    }
}
