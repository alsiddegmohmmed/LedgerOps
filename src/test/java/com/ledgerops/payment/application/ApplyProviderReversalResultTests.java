package com.ledgerops.payment.application;

import com.ledgerops.audit.api.AuditAppendPort;
import com.ledgerops.ledger.api.LedgerPostingEvidence;
import com.ledgerops.ledger.api.ReversalLedger;
import com.ledgerops.messaging.api.ConsumerMessageStore;
import com.ledgerops.messaging.api.InboxResult;
import com.ledgerops.messaging.api.IncomingMessage;
import com.ledgerops.messaging.api.MessageOutbox;
import com.ledgerops.messaging.api.ProducerName;
import com.ledgerops.messaging.api.StoredOutboxMessage;
import com.ledgerops.merchant.api.MerchantReference;
import com.ledgerops.payment.domain.AttemptSubjectType;
import com.ledgerops.payment.domain.CustomerId;
import com.ledgerops.payment.domain.IdempotencyKey;
import com.ledgerops.payment.domain.Money;
import com.ledgerops.payment.domain.Payment;
import com.ledgerops.payment.domain.PaymentAttempt;
import com.ledgerops.payment.domain.PaymentAttemptId;
import com.ledgerops.payment.domain.PaymentId;
import com.ledgerops.payment.domain.PaymentMethodCategory;
import com.ledgerops.payment.domain.PaymentStatus;
import com.ledgerops.payment.domain.ProviderId;
import com.ledgerops.payment.domain.Reversal;
import com.ledgerops.payment.domain.ReversalId;
import com.ledgerops.payment.domain.ReversalStatus;
import com.ledgerops.provider.api.ProviderEvidence;
import com.ledgerops.provider.api.ProviderOperationType;
import com.ledgerops.provider.api.ProviderResultCategory;
import com.ledgerops.provider.api.RetryDisposition;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApplyProviderReversalResultTests {

    private static final Instant NOW = Instant.parse("2026-08-10T14:00:00Z");

    @Test
    void successfulProviderResultPostsCompensationAndChangesBothOwnersAtomically() {
        Payment payment = completedPayment();
        Reversal requested = Reversal.request(
                ReversalId.newId(), payment, UUID.randomUUID(), "Customer request", NOW);
        Reversal processing = requested.startProcessing(NOW.plusSeconds(1));
        PaymentAttempt attempt = reversalAttempt(payment, processing);
        UUID messageId = UUID.randomUUID();
        UUID evidenceId = UUID.randomUUID();
        UUID providerResultId = UUID.randomUUID();
        PaymentProviderResultCommand command = new PaymentProviderResultCommand(
                messageId,
                payment.tenantId(),
                payment.id().value(),
                ProviderOperationType.REVERSAL,
                processing.id().value(),
                attempt.attemptId().value(),
                evidenceId,
                providerResultId,
                "SIMULATOR",
                attempt.providerIdempotencyKey(),
                ProviderResultCategory.SUCCESS,
                RetryDisposition.NOT_RETRYABLE,
                "reversal-provider-reference",
                "SUBMISSION_RESPONSE",
                NOW.plusSeconds(5),
                UUID.randomUUID()
        );

        ConsumerMessageStore inbox = mock(ConsumerMessageStore.class);
        ProviderEvidenceQueryStub evidenceQuery = new ProviderEvidenceQueryStub();
        PaymentCompletionStore payments = mock(PaymentCompletionStore.class);
        PaymentProviderResultStore providerResults = mock(PaymentProviderResultStore.class);
        ReversalStore reversals = mock(ReversalStore.class);
        ReversalProviderResultStore reversalResults = mock(ReversalProviderResultStore.class);
        ReversalLedger ledger = mock(ReversalLedger.class);
        MessageOutbox outbox = mock(MessageOutbox.class);
        AuditAppendPort audit = mock(AuditAppendPort.class);
        PaymentLifecycleEventAppender paymentEvents = mock(PaymentLifecycleEventAppender.class);
        UUID ledgerTransactionId = UUID.randomUUID();

        when(inbox.recordProcessed(any())).thenReturn(InboxResult.PROCESSED);
        evidenceQuery.evidence = new ProviderEvidence(
                evidenceId,
                payment.tenantId(),
                payment.id().value(),
                ProviderOperationType.REVERSAL,
                processing.id().value(),
                attempt.attemptId().value(),
                "SIMULATOR",
                attempt.providerIdempotencyKey(),
                providerResultId,
                command.providerReference(),
                ProviderResultCategory.SUCCESS,
                RetryDisposition.NOT_RETRYABLE,
                true,
                false,
                command.evidenceOrigin(),
                command.observedAt()
        );
        when(payments.lockByTenantAndId(payment.tenantId(), payment.id()))
                .thenReturn(Optional.of(new VersionedPayment(payment, 8)));
        when(reversals.lockByTenantAndId(payment.tenantId(), processing.id()))
                .thenReturn(Optional.of(processing));
        when(providerResults.findAttemptById(
                payment.tenantId(), payment.id(), attempt.attemptId().value()))
                .thenReturn(Optional.of(attempt));
        when(providerResults.findAcceptedFinalResult(payment.tenantId(), payment.id()))
                .thenReturn(Optional.of(new AcceptedFinalProviderResult(
                        payment.tenantId(), payment.id().value(), UUID.randomUUID(),
                        UUID.randomUUID(), UUID.randomUUID(), ProviderResultCategory.SUCCESS,
                        "original-provider-reference", NOW.minusSeconds(60))));
        when(reversalResults.findAcceptedFinalResult(
                payment.tenantId(), processing.id())).thenReturn(Optional.empty());
        when(reversals.compareAndSet(any(), any(Long.class))).thenReturn(true);
        when(payments.compareAndSet(any(), any(Long.class))).thenReturn(true);
        when(ledger.postCompensation(any())).thenReturn(new LedgerPostingEvidence(
                ledgerTransactionId,
                payment.tenantId(),
                "REVERSAL",
                processing.id().value(),
                Currency.getInstance("SAR"),
                new BigDecimal("125.00"),
                new BigDecimal("125.00"),
                java.util.List.of(),
                Optional.of(UUID.randomUUID())
        ));
        when(outbox.appendOrGet(any())).thenReturn(storedMessage());

        ApplyProviderReversalResult service = new ApplyProviderReversalResult(
                inbox,
                evidenceQuery,
                payments,
                providerResults,
                reversals,
                reversalResults,
                ledger,
                outbox,
                audit,
                paymentEvents,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );

        ReversalProviderResultResult result = service.apply(
                new IncomingMessage(
                        ApplyProviderReversalResult.CONSUMER_NAME,
                        messageId,
                        payment.tenantId(),
                        "ProviderReversalResultObserved"
                ),
                command
        );

        assertEquals(ReversalStatus.COMPLETED, result.reversalStatus());
        assertEquals(PaymentStatus.REVERSED, result.paymentStatus());
        assertEquals(ReversalProviderResultOutcome.COMPLETED, result.outcome());
        assertEquals(ledgerTransactionId, result.ledgerTransactionId());
        verify(reversalResults).insertAcceptedFinalResult(any());
        verify(payments).compareAndSet(any(), org.mockito.ArgumentMatchers.eq(8L));
    }

    private PaymentAttempt reversalAttempt(Payment payment, Reversal reversal) {
        return new PaymentAttempt(
                PaymentAttemptId.from(UUID.randomUUID()),
                payment.tenantId(),
                payment.id(),
                AttemptSubjectType.REVERSAL,
                reversal.id().value(),
                1,
                ProviderId.SIMULATOR,
                "reversal:" + reversal.id().value(),
                NOW,
                payment.merchantReference().value(),
                payment.customerId(),
                reversal.amount(),
                payment.paymentMethodCategory(),
                RequestIntentHash.calculateReversal(
                        payment, reversal, "original-provider-reference")
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
                IdempotencyKey.from("payment-" + UUID.randomUUID()),
                PaymentStatus.COMPLETED
        );
    }

    private StoredOutboxMessage storedMessage() {
        return new StoredOutboxMessage(
                UUID.randomUUID(), UUID.randomUUID(), ProducerName.PAYMENT,
                "reversal-lifecycle:test:1", "a".repeat(64),
                "ReversalCompleted", 1, UUID.randomUUID(), UUID.randomUUID(),
                "ledgerops.payment.lifecycle.v1", UUID.randomUUID().toString(), "{}",
                UUID.randomUUID(), UUID.randomUUID(), NOW);
    }

    private static final class ProviderEvidenceQueryStub
            implements com.ledgerops.provider.api.ProviderEvidenceQuery {
        private ProviderEvidence evidence;

        @Override
        public Optional<ProviderEvidence> find(UUID tenantId, UUID evidenceId) {
            return Optional.ofNullable(evidence);
        }
    }
}
