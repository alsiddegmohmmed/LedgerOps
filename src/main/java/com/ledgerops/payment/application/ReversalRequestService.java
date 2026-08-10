package com.ledgerops.payment.application;

import com.ledgerops.audit.api.AuditAppendPort;
import com.ledgerops.identity.api.AuthenticatedPrincipal;
import com.ledgerops.identity.api.AuthorizationPermissionDeniedException;
import com.ledgerops.identity.api.AuthorizationResourceNotFoundException;
import com.ledgerops.identity.api.AuthorizedRequestContext;
import com.ledgerops.ledger.api.LedgerPostingEntryEvidence;
import com.ledgerops.ledger.api.LedgerPostingEvidence;
import com.ledgerops.ledger.api.PaymentSuccessLedger;
import com.ledgerops.messaging.api.MessageOutbox;
import com.ledgerops.payment.domain.Payment;
import com.ledgerops.payment.domain.PaymentId;
import com.ledgerops.payment.domain.PaymentStatus;
import com.ledgerops.payment.domain.Reversal;
import com.ledgerops.payment.domain.ReversalId;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;

@Service
public class ReversalRequestService {

    private final PaymentCompletionStore payments;
    private final ReversalStore reversals;
    private final PaymentSuccessLedger ledger;
    private final AuditAppendPort audit;
    private final MessageOutbox outbox;
    private final Clock clock;

    public ReversalRequestService(
            PaymentCompletionStore payments,
            ReversalStore reversals,
            PaymentSuccessLedger ledger,
            AuditAppendPort audit,
            MessageOutbox outbox,
            Clock clock
    ) {
        this.payments = payments;
        this.reversals = reversals;
        this.ledger = ledger;
        this.audit = audit;
        this.outbox = outbox;
        this.clock = clock;
    }

    @Transactional
    public ReversalRequestResult request(ReversalRequestCommand command) {
        authorize(command);
        if (!command.confirmation()) {
            throw new IllegalArgumentException("Reversal request requires explicit confirmation");
        }

        VersionedPayment current = payments.lockByTenantAndId(
                        command.tenantId(),
                        PaymentId.from(command.paymentId()))
                .orElseThrow(() -> new ReversalRequestConsistencyException(
                        "Payment does not exist in the requested Tenant"));
        Payment payment = current.payment();
        if (!command.authorization().allowsMerchant(payment.merchantReference().value())) {
            throw new AuthorizationResourceNotFoundException();
        }
        if (payment.status() != PaymentStatus.COMPLETED) {
            throw new ReversalRequestConsistencyException(
                    "Only a COMPLETED Payment can be reversed");
        }

        LedgerPostingEvidence posting = ledger.findByPaymentSource(
                        payment.tenantId(),
                        payment.id().value())
                .orElseThrow(() -> new ReversalRequestConsistencyException(
                        "COMPLETED Payment has no exact ADR-020 Ledger posting"));
        if (!matchesPaymentSuccessPosting(payment, posting)) {
            throw new ReversalRequestConsistencyException(
                    "Payment Ledger posting does not match the exact ADR-020 template");
        }
        if (reversals.findByTenantAndPayment(payment.tenantId(), payment.id()).isPresent()) {
            throw new ReversalAlreadyExistsException();
        }

        UUID requestedBy = command.authorization().applicationUserId();
        if (requestedBy == null) {
            throw new AuthorizationPermissionDeniedException("reversal:request");
        }
        Instant requestedAt = clock.instant();
        Reversal reversal = Reversal.request(
                ReversalId.newId(),
                payment,
                requestedBy,
                command.reason(),
                requestedAt
        );
        reversals.insert(reversal);

        AuthenticatedPrincipal actor = command.actor();
        AuthorizedRequestContext authorization = command.authorization();
        audit.appendAction(
                actor.issuer(),
                actor.subject(),
                actor.principalType(),
                reversal.tenantId(),
                "payment.reversal-requested",
                "reversal",
                reversal.id().value().toString(),
                reversal.requestReason(),
                "{\"paymentId\":\"" + reversal.paymentId().value()
                        + "\",\"merchantId\":\"" + reversal.merchantId() + "\"}",
                authorization.correlationId()
        );
        UUID correlationId = correlationId(authorization.correlationId());
        outbox.appendOrGet(ReversalLifecycleEventFactory.requested(
                reversal,
                correlationId,
                reversal.id().value(),
                requestedAt
        ));
        return new ReversalRequestResult(reversal);
    }

    private void authorize(ReversalRequestCommand command) {
        AuthorizedRequestContext authorization = command.authorization();
        if (!command.tenantId().equals(authorization.tenantId())) {
            throw new AuthorizationResourceNotFoundException();
        }
        if (!authorization.canRequestReversal()) {
            throw new AuthorizationPermissionDeniedException("reversal:request");
        }
        if (command.reason() == null || command.reason().isBlank()) {
            throw new IllegalArgumentException("Reversal request reason must not be blank");
        }
    }

    private boolean matchesPaymentSuccessPosting(Payment payment, LedgerPostingEvidence posting) {
        BigDecimal amount = payment.amount().amount();
        Currency currency = payment.amount().currency();
        return posting.tenantId().equals(payment.tenantId())
                && "PAYMENT".equals(posting.sourceType())
                && posting.sourceId().equals(payment.id().value())
                && posting.currency().equals(currency)
                && sameAmount(posting.totalDebits(), amount)
                && sameAmount(posting.totalCredits(), amount)
                && posting.entries().size() == 2
                && posting.entries().stream().filter(entry -> entryMatches(
                        entry, "PROVIDER_CLEARING", "DEBIT", amount, currency)).count() == 1
                && posting.entries().stream().filter(entry -> entryMatches(
                        entry, "MERCHANT_PAYABLE", "CREDIT", amount, currency)).count() == 1
                && posting.compensatesTransactionId().isEmpty();
    }

    private boolean entryMatches(
            LedgerPostingEntryEvidence entry,
            String accountCode,
            String direction,
            BigDecimal amount,
            Currency currency
    ) {
        return entry.accountCode().equals(accountCode)
                && entry.direction().equals(direction)
                && sameAmount(entry.amount(), amount)
                && entry.currency().equals(currency);
    }

    private boolean sameAmount(BigDecimal left, BigDecimal right) {
        return left.compareTo(right) == 0;
    }

    private UUID correlationId(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
        }
    }
}
