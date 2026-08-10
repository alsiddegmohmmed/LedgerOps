package com.ledgerops.payment.application;

import com.ledgerops.audit.api.AuditAppendPort;
import com.ledgerops.identity.api.AuthenticatedPrincipal;
import com.ledgerops.identity.api.AuthorizedRequestContext;
import com.ledgerops.identity.domain.Permission;
import com.ledgerops.identity.domain.PrincipalType;
import com.ledgerops.identity.domain.ScopeMode;
import com.ledgerops.ledger.api.LedgerPostingEntryEvidence;
import com.ledgerops.ledger.api.LedgerPostingEvidence;
import com.ledgerops.ledger.api.PaymentSuccessLedger;
import com.ledgerops.messaging.api.MessageOutbox;
import com.ledgerops.merchant.api.MerchantReference;
import com.ledgerops.payment.domain.CustomerId;
import com.ledgerops.payment.domain.IdempotencyKey;
import com.ledgerops.payment.domain.Money;
import com.ledgerops.payment.domain.Payment;
import com.ledgerops.payment.domain.PaymentId;
import com.ledgerops.payment.domain.PaymentMethodCategory;
import com.ledgerops.payment.domain.PaymentStatus;
import com.ledgerops.payment.domain.Reversal;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReversalRequestServiceTests {

    private static final Instant NOW = Instant.parse("2026-08-10T12:00:00Z");

    @Test
    void createsRequestedReversalOnlyAfterExactPaymentPostingValidation() {
        Payment payment = completedPayment();
        PaymentCompletionStore payments = mock(PaymentCompletionStore.class);
        ReversalStore reversals = mock(ReversalStore.class);
        PaymentSuccessLedger ledger = mock(PaymentSuccessLedger.class);
        AuditAppendPort audit = mock(AuditAppendPort.class);
        MessageOutbox outbox = mock(MessageOutbox.class);

        when(payments.lockByTenantAndId(payment.tenantId(), payment.id()))
                .thenReturn(Optional.of(new VersionedPayment(payment, 4)));
        when(ledger.findByPaymentSource(payment.tenantId(), payment.id().value()))
                .thenReturn(Optional.of(exactPaymentPosting(payment)));
        when(reversals.findByTenantAndPayment(payment.tenantId(), payment.id()))
                .thenReturn(Optional.empty());

        ReversalRequestResult result = service(
                payments, reversals, ledger, audit, outbox
        ).request(command(payment, true, "Customer requested a full reversal"));

        Reversal reversal = result.reversal();
        assertEquals(payment.id(), reversal.paymentId());
        assertEquals(payment.tenantId(), reversal.tenantId());
        assertEquals(payment.amount(), reversal.amount());
        assertEquals(PaymentStatus.COMPLETED, payment.status());
        verify(reversals).insert(reversal);
        verify(audit).appendAction(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any()
        );
        verify(outbox).appendOrGet(any());
    }

    @Test
    void rejectsCompletedPaymentWithoutExactPosting() {
        Payment payment = completedPayment();
        PaymentCompletionStore payments = mock(PaymentCompletionStore.class);
        ReversalStore reversals = mock(ReversalStore.class);
        PaymentSuccessLedger ledger = mock(PaymentSuccessLedger.class);
        AuditAppendPort audit = mock(AuditAppendPort.class);
        MessageOutbox outbox = mock(MessageOutbox.class);

        when(payments.lockByTenantAndId(payment.tenantId(), payment.id()))
                .thenReturn(Optional.of(new VersionedPayment(payment, 0)));
        when(ledger.findByPaymentSource(payment.tenantId(), payment.id().value()))
                .thenReturn(Optional.of(mismatchedPaymentPosting(payment)));

        assertThrows(
                ReversalRequestConsistencyException.class,
                () -> service(payments, reversals, ledger, audit, outbox)
                        .request(command(payment, true, "Customer requested a full reversal"))
        );

        verify(reversals, never()).insert(any());
        verify(outbox, never()).appendOrGet(any());
    }

    @Test
    void requiresConfirmationBeforePaymentLock() {
        PaymentCompletionStore payments = mock(PaymentCompletionStore.class);
        ReversalStore reversals = mock(ReversalStore.class);
        PaymentSuccessLedger ledger = mock(PaymentSuccessLedger.class);
        AuditAppendPort audit = mock(AuditAppendPort.class);
        MessageOutbox outbox = mock(MessageOutbox.class);
        Payment payment = completedPayment();

        assertThrows(
                IllegalArgumentException.class,
                () -> service(payments, reversals, ledger, audit, outbox)
                        .request(command(payment, false, "Customer requested a full reversal"))
        );

        verify(payments, never()).lockByTenantAndId(any(), any());
    }

    private ReversalRequestService service(
            PaymentCompletionStore payments,
            ReversalStore reversals,
            PaymentSuccessLedger ledger,
            AuditAppendPort audit,
            MessageOutbox outbox
    ) {
        return new ReversalRequestService(
                payments, reversals, ledger, audit, outbox,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    private ReversalRequestCommand command(Payment payment, boolean confirmation, String reason) {
        UUID userId = UUID.randomUUID();
        AuthorizedRequestContext context = new AuthorizedRequestContext(
                PrincipalType.HUMAN,
                userId,
                null,
                payment.tenantId(),
                ScopeMode.TENANT_WIDE,
                Set.of(),
                Set.of(Permission.REVERSAL_REQUEST),
                "correlation-1"
        );
        return new ReversalRequestCommand(
                payment.tenantId(),
                payment.id().value(),
                confirmation,
                reason,
                context,
                new AuthenticatedPrincipal("HUMAN", "issuer", "subject")
        );
    }

    private Payment completedPayment() {
        UUID tenantId = UUID.randomUUID();
        return Payment.rehydrate(
                PaymentId.newId(),
                MerchantReference.from(tenantId, UUID.randomUUID()),
                CustomerId.from(UUID.randomUUID()),
                Money.of(new BigDecimal("125.00"), Currency.getInstance("SAR")),
                PaymentMethodCategory.from("CARD"),
                IdempotencyKey.from("idempotency-" + UUID.randomUUID()),
                PaymentStatus.COMPLETED
        );
    }

    private LedgerPostingEvidence exactPaymentPosting(Payment payment) {
        return new LedgerPostingEvidence(
                UUID.randomUUID(),
                payment.tenantId(),
                "PAYMENT",
                payment.id().value(),
                payment.amount().currency(),
                payment.amount().amount(),
                payment.amount().amount(),
                List.of(
                        entry("PROVIDER_CLEARING", "DEBIT", payment),
                        entry("MERCHANT_PAYABLE", "CREDIT", payment)
                ),
                Optional.empty()
        );
    }

    private LedgerPostingEvidence mismatchedPaymentPosting(Payment payment) {
        LedgerPostingEvidence exact = exactPaymentPosting(payment);
        return new LedgerPostingEvidence(
                exact.transactionId(),
                exact.tenantId(),
                exact.sourceType(),
                exact.sourceId(),
                exact.currency(),
                exact.totalDebits().add(BigDecimal.ONE),
                exact.totalCredits(),
                exact.entries(),
                exact.compensatesTransactionId()
        );
    }

    private LedgerPostingEntryEvidence entry(String account, String direction, Payment payment) {
        return new LedgerPostingEntryEvidence(
                UUID.randomUUID(),
                account,
                direction,
                payment.amount().amount(),
                payment.amount().currency()
        );
    }
}
