package com.ledgerops.payment.application;

import com.ledgerops.audit.api.AuditAppendPort;
import com.ledgerops.messaging.api.MessageOutbox;
import com.ledgerops.messaging.api.OutboxConsistencyException;
import com.ledgerops.messaging.api.OutboxMessageDraft;
import com.ledgerops.messaging.api.ProducerName;
import com.ledgerops.messaging.api.StoredOutboxMessage;
import com.ledgerops.payment.domain.AttemptSubjectType;
import com.ledgerops.payment.domain.Payment;
import com.ledgerops.payment.domain.PaymentAttempt;
import com.ledgerops.payment.domain.PaymentAttemptId;
import com.ledgerops.payment.domain.PaymentId;
import com.ledgerops.payment.domain.PaymentStatus;
import com.ledgerops.payment.domain.ProviderId;
import com.ledgerops.payment.domain.Reversal;
import com.ledgerops.payment.domain.ReversalId;
import com.ledgerops.payment.domain.ReversalStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
public class StartReversalProcessing {

    private final PaymentCompletionStore payments;
    private final PaymentSubmissionStore attempts;
    private final PaymentProviderResultStore providerResults;
    private final ReversalStore reversals;
    private final MessageOutbox outbox;
    private final AuditAppendPort audit;
    private final Clock clock;

    public StartReversalProcessing(
            PaymentCompletionStore payments,
            PaymentSubmissionStore attempts,
            PaymentProviderResultStore providerResults,
            ReversalStore reversals,
            MessageOutbox outbox,
            AuditAppendPort audit,
            Clock clock
    ) {
        this.payments = payments;
        this.attempts = attempts;
        this.providerResults = providerResults;
        this.reversals = reversals;
        this.outbox = outbox;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public ReversalProcessingResult process(ReversalProcessingCommand command) {
        VersionedPayment currentPayment = payments.lockByTenantAndId(
                        command.tenantId(), PaymentId.from(command.paymentId()))
                .orElseThrow(() -> new ReversalProcessingConsistencyException(
                        "Originating Payment does not exist in the requested Tenant"));
        return processLockedPayment(command, currentPayment);
    }

    private ReversalProcessingResult processLockedPayment(
            ReversalProcessingCommand command,
            VersionedPayment currentPayment
    ) {
        Payment payment = currentPayment.payment();
        Reversal reversal = reversals.lockByTenantAndId(
                        command.tenantId(), ReversalId.from(command.reversalId()))
                .orElseThrow(() -> new ReversalProcessingConsistencyException(
                        "Reversal does not exist in the requested Tenant"));
        if (!payment.id().equals(reversal.paymentId())) {
            throw new ReversalProcessingConsistencyException(
                    "Reversal does not belong to the locked Payment");
        }
        if (payment.status() != PaymentStatus.COMPLETED) {
            throw new ReversalProcessingConsistencyException(
                    "Only a COMPLETED Payment can enter Reversal processing");
        }
        AcceptedFinalProviderResult accepted = providerResults.findAcceptedFinalResult(
                        payment.tenantId(), payment.id())
                .filter(result -> result.finalCategory()
                        == com.ledgerops.provider.api.ProviderResultCategory.SUCCESS)
                .orElseThrow(() -> new ReversalProcessingConsistencyException(
                        "COMPLETED Payment has no accepted successful Provider result"));
        String originalProviderReference = accepted.providerReference();
        if (originalProviderReference == null || originalProviderReference.isBlank()) {
            throw new ReversalProcessingConsistencyException(
                    "Accepted Provider result has no original Provider reference");
        }

        Optional<PaymentAttempt> existing = attempts.findAttempt(
                payment.tenantId(), payment.id(), AttemptSubjectType.REVERSAL,
                reversal.id().value(), 1);
        if (reversal.status() == ReversalStatus.PROCESSING) {
            PaymentAttempt attempt = existing.orElseThrow(() ->
                    new ReversalProcessingConsistencyException(
                            "PROCESSING Reversal has no sequence-1 Reversal attempt"));
            requireAttemptMatches(payment, reversal, attempt, originalProviderReference);
            StoredOutboxMessage message = requireExistingSubmission(
                    attempt, reversal, originalProviderReference, command);
            return new ReversalProcessingResult(
                    reversal, attempt, message.outboxId(), message.messageId(), true);
        }
        if (reversal.status() != ReversalStatus.REQUESTED) {
            throw new ReversalProcessingConsistencyException(
                    "Only a REQUESTED Reversal can enter initial processing");
        }
        if (existing.isPresent()) {
            throw new ReversalProcessingConsistencyException(
                    "REQUESTED Reversal already has a durable Reversal attempt");
        }

        Instant initiatedAt = clock.instant();
        PaymentAttempt attempt = new PaymentAttempt(
                PaymentAttemptId.from(UUID.randomUUID()),
                payment.tenantId(),
                payment.id(),
                AttemptSubjectType.REVERSAL,
                reversal.id().value(),
                1,
                ProviderId.SIMULATOR,
                "reversal:" + reversal.id().value().toString().toLowerCase(java.util.Locale.ROOT),
                initiatedAt,
                payment.merchantReference().value(),
                payment.customerId(),
                reversal.amount(),
                payment.paymentMethodCategory(),
                RequestIntentHash.calculateReversal(
                        payment, reversal, originalProviderReference));
        Reversal processing = reversal.startProcessing(initiatedAt);
        attempts.insertAttempt(attempt);
        if (!reversals.compareAndSet(processing, reversal.version())) {
            throw new ReversalProcessingConsistencyException(
                    "Reversal changed while starting processing");
        }
        long aggregateVersion = Math.addExact(reversal.version(), 1);
        StoredOutboxMessage message = outbox.appendOrGet(
                ReversalSubmissionMessageFactory.draft(
                        attempt, reversal, originalProviderReference,
                        command.correlationId(), command.causationId(), initiatedAt));
        outbox.appendOrGet(ReversalLifecycleEventFactory.processingStarted(
                processing, aggregateVersion, attempt.attemptId().value(),
                command.correlationId(), command.causationId(), initiatedAt));
        audit.appendAction(
                "system", "reversal-processing", "SYSTEM", reversal.tenantId(),
                "payment.reversal-processing-started", "reversal",
                reversal.id().value().toString(), "Reversal processing started",
                "{\"paymentId\":\"" + reversal.paymentId().value()
                        + "\",\"attemptId\":\"" + attempt.attemptId().value() + "\"}",
                command.correlationId().toString());
        return new ReversalProcessingResult(
                processing, attempt, message.outboxId(), message.messageId(), false);
    }

    private void requireAttemptMatches(
            Payment payment,
            Reversal reversal,
            PaymentAttempt attempt,
            String originalProviderReference
    ) {
        if (!attempt.tenantId().equals(payment.tenantId())
                || !attempt.paymentId().equals(payment.id())
                || attempt.subjectType() != AttemptSubjectType.REVERSAL
                || !attempt.subjectId().equals(reversal.id().value())
                || attempt.sequence() != 1
                || attempt.providerId() != ProviderId.SIMULATOR
                || !attempt.providerIdempotencyKey().equals(
                "reversal:" + reversal.id().value().toString().toLowerCase(java.util.Locale.ROOT))
                || !attempt.merchantId().equals(reversal.merchantId())
                || !attempt.customerId().equals(payment.customerId())
                || !attempt.amount().equals(reversal.amount())
                || !attempt.paymentMethodCategory().equals(payment.paymentMethodCategory())
                || !attempt.requestIntentHash().equals(
                RequestIntentHash.calculateReversal(payment, reversal, originalProviderReference))) {
            throw new ReversalProcessingConsistencyException(
                    "PROCESSING Reversal has mismatched attempt evidence");
        }
    }

    private StoredOutboxMessage requireExistingSubmission(
            PaymentAttempt attempt,
            Reversal reversal,
            String originalProviderReference,
            ReversalProcessingCommand command
    ) {
        try {
            return outbox.requireExistingEquivalent(ReversalSubmissionMessageFactory.draft(
                    attempt, reversal, originalProviderReference,
                    command.correlationId(), command.causationId(), attempt.initiatedAt()));
        } catch (OutboxConsistencyException exception) {
            throw new ReversalProcessingConsistencyException(
                    "PROCESSING Reversal has missing or mismatched provider command");
        }
    }
}
