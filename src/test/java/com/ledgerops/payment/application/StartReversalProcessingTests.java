package com.ledgerops.payment.application;

import com.ledgerops.audit.api.AuditAppendPort;
import com.ledgerops.messaging.api.MessageOutbox;
import com.ledgerops.messaging.api.ProducerName;
import com.ledgerops.messaging.api.StoredOutboxMessage;
import com.ledgerops.merchant.api.MerchantReference;
import com.ledgerops.payment.domain.AttemptSubjectType;
import com.ledgerops.payment.domain.CustomerId;
import com.ledgerops.payment.domain.IdempotencyKey;
import com.ledgerops.payment.domain.Money;
import com.ledgerops.payment.domain.Payment;
import com.ledgerops.payment.domain.PaymentId;
import com.ledgerops.payment.domain.PaymentMethodCategory;
import com.ledgerops.payment.domain.PaymentStatus;
import com.ledgerops.payment.domain.Reversal;
import com.ledgerops.payment.domain.ReversalId;
import com.ledgerops.payment.domain.ReversalStatus;
import com.ledgerops.provider.api.ProviderResultCategory;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StartReversalProcessingTests {

    private static final Instant NOW = Instant.parse("2026-08-10T12:00:00Z");

    @Test
    void locksPaymentThenReversalAndCreatesTypedAttemptAndCommands() {
        Payment payment = completedPayment();
        Reversal reversal = Reversal.request(
                ReversalId.newId(), payment, UUID.randomUUID(),
                "Customer requested a full reversal", NOW.minusSeconds(30));
        PaymentCompletionStore payments = mock(PaymentCompletionStore.class);
        PaymentSubmissionStore attempts = mock(PaymentSubmissionStore.class);
        PaymentProviderResultStore providerResults = mock(PaymentProviderResultStore.class);
        ReversalStore reversals = mock(ReversalStore.class);
        MessageOutbox outbox = mock(MessageOutbox.class);
        AuditAppendPort audit = mock(AuditAppendPort.class);
        StoredOutboxMessage stored = storedMessage();

        when(payments.lockByTenantAndId(payment.tenantId(), payment.id()))
                .thenReturn(Optional.of(new VersionedPayment(payment, 4)));
        when(reversals.lockByTenantAndId(payment.tenantId(), reversal.id()))
                .thenReturn(Optional.of(reversal));
        when(providerResults.findAcceptedFinalResult(payment.tenantId(), payment.id()))
                .thenReturn(Optional.of(new AcceptedFinalProviderResult(
                        payment.tenantId(), payment.id().value(), UUID.randomUUID(),
                        UUID.randomUUID(), UUID.randomUUID(), ProviderResultCategory.SUCCESS,
                        "provider-reference-1", NOW.minusSeconds(60))));
        when(attempts.findAttempt(
                payment.tenantId(), payment.id(), AttemptSubjectType.REVERSAL,
                reversal.id().value(), 1)).thenReturn(Optional.empty());
        when(reversals.compareAndSet(any(), org.mockito.ArgumentMatchers.eq(0L)))
                .thenReturn(true);
        when(outbox.appendOrGet(any())).thenReturn(stored);

        ReversalProcessingResult result = service(
                payments, attempts, providerResults, reversals, outbox, audit
        ).process(new ReversalProcessingCommand(
                payment.tenantId(), payment.id().value(), reversal.id().value(),
                UUID.randomUUID(), UUID.randomUUID()));

        assertEquals(ReversalStatus.PROCESSING, result.reversal().status());
        assertEquals(AttemptSubjectType.REVERSAL, result.attempt().subjectType());
        assertEquals(reversal.id().value(), result.attempt().subjectId());
        assertEquals("reversal:" + reversal.id().value(),
                result.attempt().providerIdempotencyKey());
        assertTrue(result.attempt().requestIntentHash().matches("[0-9a-f]{64}"));
        assertTrue(!result.replay());
        verify(attempts).insertAttempt(result.attempt());
        verify(outbox, org.mockito.Mockito.times(2)).appendOrGet(any());
        InOrder lockOrder = inOrder(payments, reversals);
        lockOrder.verify(payments)
                .lockByTenantAndId(payment.tenantId(), payment.id());
        lockOrder.verify(reversals)
                .lockByTenantAndId(payment.tenantId(), reversal.id());
    }

    @Test
    void refusesToCreateAttemptWithoutAcceptedSuccessfulProviderEvidence() {
        Payment payment = completedPayment();
        Reversal reversal = Reversal.request(
                ReversalId.newId(), payment, UUID.randomUUID(), "Duplicate payment", NOW);
        PaymentCompletionStore payments = mock(PaymentCompletionStore.class);
        PaymentSubmissionStore attempts = mock(PaymentSubmissionStore.class);
        PaymentProviderResultStore providerResults = mock(PaymentProviderResultStore.class);
        ReversalStore reversals = mock(ReversalStore.class);
        MessageOutbox outbox = mock(MessageOutbox.class);
        AuditAppendPort audit = mock(AuditAppendPort.class);

        when(payments.lockByTenantAndId(payment.tenantId(), payment.id()))
                .thenReturn(Optional.of(new VersionedPayment(payment, 0)));
        when(reversals.lockByTenantAndId(payment.tenantId(), reversal.id()))
                .thenReturn(Optional.of(reversal));
        when(providerResults.findAcceptedFinalResult(payment.tenantId(), payment.id()))
                .thenReturn(Optional.empty());

        assertThrows(ReversalProcessingConsistencyException.class, () -> service(
                payments, attempts, providerResults, reversals, outbox, audit
        ).process(new ReversalProcessingCommand(
                payment.tenantId(), payment.id().value(), reversal.id().value(),
                UUID.randomUUID(), UUID.randomUUID())));

        verify(attempts, never()).insertAttempt(any());
        verify(outbox, never()).appendOrGet(any());
    }

    private StartReversalProcessing service(
            PaymentCompletionStore payments,
            PaymentSubmissionStore attempts,
            PaymentProviderResultStore providerResults,
            ReversalStore reversals,
            MessageOutbox outbox,
            AuditAppendPort audit
    ) {
        return new StartReversalProcessing(
                payments, attempts, providerResults, reversals, outbox, audit,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private Payment completedPayment() {
        UUID tenantId = UUID.randomUUID();
        return Payment.rehydrate(
                PaymentId.newId(),
                MerchantReference.from(tenantId, UUID.randomUUID()),
                CustomerId.from(UUID.randomUUID()),
                Money.of(new BigDecimal("125.00"), java.util.Currency.getInstance("SAR")),
                PaymentMethodCategory.from("CARD"),
                IdempotencyKey.from("payment-" + UUID.randomUUID()),
                PaymentStatus.COMPLETED
        );
    }

    private StoredOutboxMessage storedMessage() {
        return new StoredOutboxMessage(
                UUID.randomUUID(), UUID.randomUUID(), ProducerName.PAYMENT,
                "reversal-submission:test", "a".repeat(64),
                "SubmitReversalToProvider", 1, UUID.randomUUID(), UUID.randomUUID(),
                "ledgerops.provider.commands.v1", UUID.randomUUID().toString(), "{}",
                UUID.randomUUID(), UUID.randomUUID(), NOW);
    }
}
