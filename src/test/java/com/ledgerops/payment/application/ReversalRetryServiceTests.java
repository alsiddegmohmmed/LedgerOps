package com.ledgerops.payment.application;

import com.ledgerops.audit.api.AuditAppendPort;
import com.ledgerops.identity.api.AuthenticatedPrincipal;
import com.ledgerops.identity.api.AuthorizedRequestContext;
import com.ledgerops.ledger.api.LedgerPostingEvidence;
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
import com.ledgerops.provider.api.ProviderEvidence;
import com.ledgerops.provider.api.ProviderOperationType;
import com.ledgerops.provider.api.ProviderResultCategory;
import com.ledgerops.provider.api.RetryDisposition;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReversalRetryServiceTests {

    private static final Instant NOW = Instant.parse("2026-08-12T12:00:00Z");

    @Test
    void createsNextTypedAttemptOnlyFromDurableSafeEvidence() {
        Payment payment = completedPayment();
        Reversal reversal = failedReversal(payment);
        AcceptedFinalProviderResult accepted = acceptedPaymentResult(payment);
        PaymentAttempt previous = reversalAttempt(payment, reversal, 1, accepted.providerReference());
        UUID evidenceId = UUID.randomUUID();

        PaymentCompletionStore payments = mock(PaymentCompletionStore.class);
        PaymentProviderResultStore providerResults = mock(PaymentProviderResultStore.class);
        ReversalRetryStore reversals = mock(ReversalRetryStore.class);
        com.ledgerops.provider.api.ProviderEvidenceQuery evidenceQuery = mock(
                com.ledgerops.provider.api.ProviderEvidenceQuery.class);
        MessageOutbox outbox = mock(MessageOutbox.class);
        AuditAppendPort audit = mock(AuditAppendPort.class);
        AuthorizedRequestContext authorization = authorization(payment);
        AuthenticatedPrincipal actor = mock(AuthenticatedPrincipal.class);
        StoredOutboxMessage stored = storedMessage();

        when(payments.lockByTenantAndId(payment.tenantId(), payment.id()))
                .thenReturn(Optional.of(new VersionedPayment(payment, 4)));
        when(reversals.lockByTenantAndId(payment.tenantId(), reversal.id()))
                .thenReturn(Optional.of(reversal));
        when(reversals.findRetryApplication(
                payment.tenantId(), reversal.id(), previous.attemptId().value()))
                .thenReturn(Optional.empty());
        when(providerResults.findAcceptedFinalResult(payment.tenantId(), payment.id()))
                .thenReturn(Optional.of(accepted));
        when(reversals.findAttemptById(
                payment.tenantId(), payment.id(), previous.attemptId().value()))
                .thenReturn(Optional.of(previous));
        when(reversals.findLatestReversalAttempt(payment.tenantId(), payment.id(), reversal.id()))
                .thenReturn(Optional.of(previous));
        when(evidenceQuery.find(payment.tenantId(), evidenceId))
                .thenReturn(Optional.of(safeEvidence(
                        payment, reversal, previous, evidenceId)));
        when(reversals.compareAndSet(any(), org.mockito.ArgumentMatchers.eq(reversal.version())))
                .thenReturn(true);
        when(outbox.appendOrGet(any())).thenReturn(stored);

        ReversalRetryResult result = service(
                payments, providerResults, reversals, evidenceQuery, outbox, audit
        ).retry(new ReversalRetryCommand(
                payment.tenantId(), payment.id().value(), reversal.id().value(),
                previous.attemptId().value(), evidenceId, "SIMULATOR", true,
                "Provider confirmed no acceptance", authorization, actor));

        assertEquals(2, result.attempt().sequence());
        assertEquals(AttemptSubjectType.REVERSAL, result.attempt().subjectType());
        assertEquals(reversal.id().value(), result.attempt().subjectId());
        assertEquals("reversal:" + reversal.id().value(),
                result.attempt().providerIdempotencyKey());
        assertFalse(result.replay());
        verify(reversals).insertAttempt(result.attempt());
        verify(reversals).insertRetryApplication(any());
        verify(outbox, org.mockito.Mockito.times(2)).appendOrGet(any());
        InOrder lockOrder = inOrder(payments, reversals);
        lockOrder.verify(payments).lockByTenantAndId(payment.tenantId(), payment.id());
        lockOrder.verify(reversals).lockByTenantAndId(payment.tenantId(), reversal.id());
    }

    @Test
    void returnsTheExistingAttemptAndCommandForAnExactRepeatedRetry() {
        Payment payment = completedPayment();
        Reversal reversal = failedReversal(payment);
        AcceptedFinalProviderResult accepted = acceptedPaymentResult(payment);
        PaymentAttempt previous = reversalAttempt(payment, reversal, 1, accepted.providerReference());
        PaymentAttempt next = reversalAttempt(payment, reversal, 2, accepted.providerReference());
        UUID evidenceId = UUID.randomUUID();
        String reason = "Provider confirmed no acceptance";
        ReversalRetryApplication application = new ReversalRetryApplication(
                payment.tenantId(), reversal.id().value(), payment.id().value(),
                previous.attemptId().value(), next.attemptId().value(), evidenceId,
                "SIMULATOR", reason, NOW.minusSeconds(10), NOW.minusSeconds(9));

        PaymentCompletionStore payments = mock(PaymentCompletionStore.class);
        PaymentProviderResultStore providerResults = mock(PaymentProviderResultStore.class);
        ReversalRetryStore reversals = mock(ReversalRetryStore.class);
        com.ledgerops.provider.api.ProviderEvidenceQuery evidenceQuery = mock(
                com.ledgerops.provider.api.ProviderEvidenceQuery.class);
        MessageOutbox outbox = mock(MessageOutbox.class);
        AuditAppendPort audit = mock(AuditAppendPort.class);
        AuthorizedRequestContext authorization = authorization(payment);
        AuthenticatedPrincipal actor = mock(AuthenticatedPrincipal.class);
        StoredOutboxMessage stored = storedMessage();

        when(payments.lockByTenantAndId(payment.tenantId(), payment.id()))
                .thenReturn(Optional.of(new VersionedPayment(payment, 4)));
        when(reversals.lockByTenantAndId(payment.tenantId(), reversal.id()))
                .thenReturn(Optional.of(reversal));
        when(reversals.findRetryApplication(
                payment.tenantId(), reversal.id(), previous.attemptId().value()))
                .thenReturn(Optional.of(application));
        when(providerResults.findAcceptedFinalResult(payment.tenantId(), payment.id()))
                .thenReturn(Optional.of(accepted));
        when(reversals.findAttemptById(
                payment.tenantId(), payment.id(), next.attemptId().value()))
                .thenReturn(Optional.of(next));
        when(outbox.requireExistingEquivalent(any())).thenReturn(stored);

        ReversalRetryResult result = service(
                payments, providerResults, reversals, evidenceQuery, outbox, audit
        ).retry(new ReversalRetryCommand(
                payment.tenantId(), payment.id().value(), reversal.id().value(),
                previous.attemptId().value(), evidenceId, "SIMULATOR", true,
                reason, authorization, actor));

        assertTrue(result.replay());
        assertEquals(next, result.attempt());
        verify(reversals, never()).insertAttempt(any());
        verify(reversals, never()).insertRetryApplication(any());
        verify(outbox).requireExistingEquivalent(any());
    }

    @Test
    void rejectsUnsafeEvidenceAndUnauthorizedRetry() {
        Payment payment = completedPayment();
        Reversal reversal = failedReversal(payment);
        AcceptedFinalProviderResult accepted = acceptedPaymentResult(payment);
        PaymentAttempt previous = reversalAttempt(payment, reversal, 1, accepted.providerReference());
        UUID evidenceId = UUID.randomUUID();

        PaymentCompletionStore payments = mock(PaymentCompletionStore.class);
        PaymentProviderResultStore providerResults = mock(PaymentProviderResultStore.class);
        ReversalRetryStore reversals = mock(ReversalRetryStore.class);
        com.ledgerops.provider.api.ProviderEvidenceQuery evidenceQuery = mock(
                com.ledgerops.provider.api.ProviderEvidenceQuery.class);
        MessageOutbox outbox = mock(MessageOutbox.class);
        AuditAppendPort audit = mock(AuditAppendPort.class);
        AuthorizedRequestContext authorization = authorization(payment);
        AuthenticatedPrincipal actor = mock(AuthenticatedPrincipal.class);

        when(payments.lockByTenantAndId(payment.tenantId(), payment.id()))
                .thenReturn(Optional.of(new VersionedPayment(payment, 4)));
        when(reversals.lockByTenantAndId(payment.tenantId(), reversal.id()))
                .thenReturn(Optional.of(reversal));
        when(reversals.findRetryApplication(
                payment.tenantId(), reversal.id(), previous.attemptId().value()))
                .thenReturn(Optional.empty());
        when(providerResults.findAcceptedFinalResult(payment.tenantId(), payment.id()))
                .thenReturn(Optional.of(accepted));
        when(reversals.findAttemptById(
                payment.tenantId(), payment.id(), previous.attemptId().value()))
                .thenReturn(Optional.of(previous));
        when(reversals.findLatestReversalAttempt(payment.tenantId(), payment.id(), reversal.id()))
                .thenReturn(Optional.of(previous));
        when(evidenceQuery.find(payment.tenantId(), evidenceId))
                .thenReturn(Optional.of(new ProviderEvidence(
                        evidenceId, payment.tenantId(), payment.id().value(),
                        ProviderOperationType.REVERSAL, reversal.id().value(),
                        previous.attemptId().value(), "SIMULATOR",
                        previous.providerIdempotencyKey(), UUID.randomUUID(), null,
                        ProviderResultCategory.TEMPORARY_FAILURE,
                        RetryDisposition.STATUS_RECOVERY_REQUIRED, false, false,
                        "SUBMISSION_RESPONSE", NOW)));

        ReversalRetryCommand command = new ReversalRetryCommand(
                payment.tenantId(), payment.id().value(), reversal.id().value(),
                previous.attemptId().value(), evidenceId, "SIMULATOR", true,
                "Retry", authorization, actor);
        assertThrows(ReversalRetryConsistencyException.class, () -> service(
                payments, providerResults, reversals, evidenceQuery, outbox, audit
        ).retry(command));

        AuthorizedRequestContext denied = mock(AuthorizedRequestContext.class);
        when(denied.tenantId()).thenReturn(payment.tenantId());
        when(denied.canRetryReversals()).thenReturn(false);
        ReversalRetryCommand deniedCommand = new ReversalRetryCommand(
                payment.tenantId(), payment.id().value(), reversal.id().value(),
                previous.attemptId().value(), evidenceId, "SIMULATOR", true,
                "Retry", denied, actor);
        assertThrows(RuntimeException.class, () -> service(
                payments, providerResults, reversals, evidenceQuery, outbox, audit
        ).retry(deniedCommand));
    }

    private ReversalRetryService service(
            PaymentCompletionStore payments,
            PaymentProviderResultStore providerResults,
            ReversalRetryStore reversals,
            com.ledgerops.provider.api.ProviderEvidenceQuery evidenceQuery,
            MessageOutbox outbox,
            AuditAppendPort audit
    ) {
        return new ReversalRetryService(
                payments, providerResults, reversals, evidenceQuery, outbox, audit,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private AuthorizedRequestContext authorization(Payment payment) {
        AuthorizedRequestContext authorization = mock(AuthorizedRequestContext.class);
        when(authorization.tenantId()).thenReturn(payment.tenantId());
        when(authorization.canRetryReversals()).thenReturn(true);
        when(authorization.allowsMerchant(payment.merchantReference().value())).thenReturn(true);
        when(authorization.correlationId()).thenReturn(UUID.randomUUID().toString());
        return authorization;
    }

    private ProviderEvidence safeEvidence(
            Payment payment,
            Reversal reversal,
            PaymentAttempt attempt,
            UUID evidenceId
    ) {
        return new ProviderEvidence(
                evidenceId, payment.tenantId(), payment.id().value(),
                ProviderOperationType.REVERSAL, reversal.id().value(),
                attempt.attemptId().value(), "SIMULATOR", attempt.providerIdempotencyKey(),
                UUID.randomUUID(), null, ProviderResultCategory.TEMPORARY_FAILURE,
                RetryDisposition.SAFE_TO_RESUBMIT, false, true,
                "SUBMISSION_RESPONSE", NOW.minusSeconds(2));
    }

    private AcceptedFinalProviderResult acceptedPaymentResult(Payment payment) {
        return new AcceptedFinalProviderResult(
                payment.tenantId(), payment.id().value(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), ProviderResultCategory.SUCCESS,
                "original-provider-reference", NOW.minusSeconds(60));
    }

    private PaymentAttempt reversalAttempt(
            Payment payment,
            Reversal reversal,
            int sequence,
            String originalProviderReference
    ) {
        return new PaymentAttempt(
                PaymentAttemptId.from(UUID.randomUUID()), payment.tenantId(), payment.id(),
                AttemptSubjectType.REVERSAL, reversal.id().value(), sequence,
                ProviderId.SIMULATOR,
                "reversal:" + reversal.id().value(),
                NOW.minusSeconds(30L - sequence), reversal.merchantId(), payment.customerId(),
                reversal.amount(), payment.paymentMethodCategory(),
                RequestIntentHash.calculateReversal(payment, reversal, originalProviderReference));
    }

    private Reversal failedReversal(Payment payment) {
        return Reversal.request(
                        ReversalId.newId(), payment, UUID.randomUUID(),
                        "Customer requested reversal", NOW.minusSeconds(120))
                .startProcessing(NOW.minusSeconds(60))
                .fail("SAFE_TO_RESUBMIT", NOW.minusSeconds(30));
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
