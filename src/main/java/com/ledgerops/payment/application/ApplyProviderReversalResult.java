package com.ledgerops.payment.application;

import com.ledgerops.audit.api.AuditAppendPort;
import com.ledgerops.ledger.api.LedgerPostingEvidence;
import com.ledgerops.ledger.api.ReversalCompensationPostingRequest;
import com.ledgerops.ledger.api.ReversalLedger;
import com.ledgerops.messaging.api.ConsumerMessageStore;
import com.ledgerops.messaging.api.InboxResult;
import com.ledgerops.messaging.api.IncomingMessage;
import com.ledgerops.messaging.api.MessageOutbox;
import com.ledgerops.messaging.api.OutboxConsistencyException;
import com.ledgerops.messaging.api.OutboxMessageDraft;
import com.ledgerops.messaging.api.ProducerName;
import com.ledgerops.messaging.api.StoredOutboxMessage;
import com.ledgerops.payment.domain.AttemptSubjectType;
import com.ledgerops.payment.domain.Payment;
import com.ledgerops.payment.domain.PaymentAttempt;
import com.ledgerops.payment.domain.PaymentId;
import com.ledgerops.payment.domain.PaymentStatus;
import com.ledgerops.payment.domain.ProviderId;
import com.ledgerops.payment.domain.Reversal;
import com.ledgerops.payment.domain.ReversalId;
import com.ledgerops.payment.domain.ReversalStatus;
import com.ledgerops.provider.api.ProviderEvidence;
import com.ledgerops.provider.api.ProviderEvidenceQuery;
import com.ledgerops.provider.api.ProviderOperationType;
import com.ledgerops.provider.api.ProviderResultCategory;
import com.ledgerops.provider.api.RetryDisposition;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
public class ApplyProviderReversalResult {

    public static final String CONSUMER_NAME = "payment-provider-reversal-result-consumer-v1";

    private final ConsumerMessageStore inbox;
    private final ProviderEvidenceQuery evidenceQuery;
    private final PaymentCompletionStore payments;
    private final PaymentProviderResultStore providerResults;
    private final ReversalStore reversals;
    private final ReversalProviderResultStore reversalResults;
    private final ReversalLedger ledger;
    private final MessageOutbox outbox;
    private final AuditAppendPort audit;
    private final PaymentLifecycleEventAppender paymentLifecycleEvents;
    private final Clock clock;

    public ApplyProviderReversalResult(
            ConsumerMessageStore inbox,
            ProviderEvidenceQuery evidenceQuery,
            PaymentCompletionStore payments,
            PaymentProviderResultStore providerResults,
            ReversalStore reversals,
            ReversalProviderResultStore reversalResults,
            ReversalLedger ledger,
            MessageOutbox outbox,
            AuditAppendPort audit,
            PaymentLifecycleEventAppender paymentLifecycleEvents,
            Clock clock
    ) {
        this.inbox = inbox;
        this.evidenceQuery = evidenceQuery;
        this.payments = payments;
        this.providerResults = providerResults;
        this.reversals = reversals;
        this.reversalResults = reversalResults;
        this.ledger = ledger;
        this.outbox = outbox;
        this.audit = audit;
        this.paymentLifecycleEvents = paymentLifecycleEvents;
        this.clock = clock;
    }

    @Transactional
    public ReversalProviderResultResult apply(
            IncomingMessage incoming,
            PaymentProviderResultCommand command
    ) {
        requireEnvelopeMatches(incoming, command);
        InboxResult inboxResult = inbox.recordProcessed(incoming);
        if (inboxResult == InboxResult.DUPLICATE) {
            LockedState state = lockState(command);
            return result(
                    state.reversal(), state.payment(),
                    ReversalProviderResultOutcome.DUPLICATE_MESSAGE, null, null
            );
        }

        ProviderEvidence evidence = evidenceQuery.find(
                command.tenantId(), command.providerEvidenceId()
        ).orElseThrow(() -> new ProviderEvidenceUnavailableException(
                command.providerEvidenceId()
        ));
        requireEvidenceMatches(command, evidence);

        LockedState state = lockState(command);
        Payment payment = state.payment();
        Reversal reversal = state.reversal();
        PaymentAttempt attempt = providerResults.findAttemptById(
                command.tenantId(), payment.id(), command.attemptId()
        ).orElseThrow(() -> consistency(command, "Reversal Payment Attempt does not exist"));
        AcceptedFinalProviderResult acceptedPayment = providerResults.findAcceptedFinalResult(
                        payment.tenantId(), payment.id())
                .filter(result -> result.finalCategory() == ProviderResultCategory.SUCCESS)
                .orElseThrow(() -> consistency(
                        command, "COMPLETED Payment has no accepted successful Provider result"));
        if (acceptedPayment.providerReference() == null
                || acceptedPayment.providerReference().isBlank()) {
            throw consistency(command, "Accepted Payment result has no original Provider reference");
        }
        requireAttemptMatches(payment, reversal, attempt, command,
                acceptedPayment.providerReference());
        requireDispositionMatches(command);

        Optional<AcceptedFinalReversalResult> accepted =
                reversalResults.findAcceptedFinalResult(
                        command.tenantId(), reversal.id());
        if (!isFinal(command.category(), command.retryDisposition())) {
            if (accepted.isPresent()) {
                return result(
                        reversal, payment, ReversalProviderResultOutcome.REPLAY, null, null
                );
            }
            requireProcessing(payment, reversal, command);
            return result(
                    reversal, payment, ReversalProviderResultOutcome.NON_FINAL, null, null
            );
        }

        if (accepted.isPresent()) {
            return replay(command, state, accepted.orElseThrow());
        }
        requireProcessing(payment, reversal, command);
        return applyFinal(command, state);
    }

    private ReversalProviderResultResult applyFinal(
            PaymentProviderResultCommand command,
            LockedState state
    ) {
        Instant appliedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        AcceptedFinalReversalResult accepted = new AcceptedFinalReversalResult(
                command.tenantId(),
                state.reversal().id().value(),
                state.payment().id().value(),
                command.attemptId(),
                command.providerEvidenceId(),
                command.providerResultId(),
                command.category(),
                command.providerReference(),
                appliedAt
        );

        if (command.category() == ProviderResultCategory.SUCCESS) {
            LedgerPostingEvidence posting = ledger.postCompensation(
                    new ReversalCompensationPostingRequest(
                            state.payment().tenantId(),
                            state.payment().id().value(),
                            state.reversal().id().value(),
                            state.reversal().amount().amount(),
                            state.reversal().amount().currency()
                    )
            );
            Reversal completed = state.reversal().complete(appliedAt);
            Payment reversed = state.payment().reverse();
            persistCompletion(command, state, completed, reversed, accepted, posting);
            StoredOutboxMessage lifecycle = outbox.appendOrGet(
                    ReversalLifecycleEventFactory.completed(
                            completed,
                            state.reversal().version() + 1,
                            command.attemptId(),
                            command.providerEvidenceId(),
                            posting.transactionId(),
                            command.correlationId(),
                            command.messageId(),
                            appliedAt
                    )
            );
            auditCompleted(command, completed, posting.transactionId());
            return result(
                    completed,
                    reversed,
                    ReversalProviderResultOutcome.COMPLETED,
                    lifecycle.messageId(),
                    posting.transactionId()
            );
        }

        Reversal failed = state.reversal().fail(command.category().name(), appliedAt);
        if (!reversals.compareAndSet(failed, state.reversal().version())) {
            throw consistency(command, "Reversal changed while applying Provider failure");
        }
        reversalResults.insertAcceptedFinalResult(accepted);
        StoredOutboxMessage lifecycle = outbox.appendOrGet(
                ReversalLifecycleEventFactory.failed(
                        failed,
                        state.reversal().version() + 1,
                        command.attemptId(),
                        command.providerEvidenceId(),
                        command.correlationId(),
                        command.messageId(),
                        appliedAt
                )
        );
        auditFailed(command, failed);
        return result(
                failed,
                state.payment(),
                ReversalProviderResultOutcome.FAILED,
                lifecycle.messageId(),
                null
        );
    }

    private void persistCompletion(
            PaymentProviderResultCommand command,
            LockedState state,
            Reversal completed,
            Payment reversed,
            AcceptedFinalReversalResult accepted,
            LedgerPostingEvidence posting
    ) {
        if (!reversals.compareAndSet(completed, state.reversal().version())) {
            throw consistency(command, "Reversal changed while applying Provider success");
        }
        if (!payments.compareAndSet(reversed, state.paymentVersion())) {
            throw consistency(command, "Payment changed while applying Reversal success");
        }
        reversalResults.insertAcceptedFinalResult(accepted);
        paymentLifecycleEvents.append(
                state.payment(),
                reversed,
                state.paymentVersion() + 1,
                "AUTOMATED",
                "REVERSAL_COMPLETED",
                command.correlationId(),
                command.messageId(),
                accepted.appliedAt()
        );
    }

    private ReversalProviderResultResult replay(
            PaymentProviderResultCommand command,
            LockedState state,
            AcceptedFinalReversalResult accepted
    ) {
        if (!acceptedMatches(command, accepted)) {
            throw consistency(command, "Provider final Reversal result conflicts with accepted evidence");
        }
        UUID ledgerTransactionId = null;
        if (accepted.finalCategory() == ProviderResultCategory.SUCCESS) {
            if (state.reversal().status() != ReversalStatus.COMPLETED
                    || state.payment().status() != PaymentStatus.REVERSED) {
                throw consistency(command, "Accepted Reversal success does not match lifecycle state");
            }
            ledgerTransactionId = ledger.postCompensation(
                    new ReversalCompensationPostingRequest(
                            state.payment().tenantId(), state.payment().id().value(),
                            state.reversal().id().value(), state.reversal().amount().amount(),
                            state.reversal().amount().currency()
                    )
            ).transactionId();
        } else if (state.reversal().status() != ReversalStatus.FAILED
                || state.payment().status() != PaymentStatus.COMPLETED) {
            throw consistency(command, "Accepted Reversal failure does not match lifecycle state");
        }
        StoredOutboxMessage lifecycle = requireExistingLifecycle(
                command, state.reversal(), accepted, ledgerTransactionId);
        return result(
                state.reversal(), state.payment(), ReversalProviderResultOutcome.REPLAY,
                lifecycle.messageId(), ledgerTransactionId
        );
    }

    private StoredOutboxMessage requireExistingLifecycle(
            PaymentProviderResultCommand command,
            Reversal reversal,
            AcceptedFinalReversalResult accepted,
            UUID ledgerTransactionId
    ) {
        OutboxMessageDraft draft;
        if (accepted.finalCategory() == ProviderResultCategory.SUCCESS) {
            draft = ReversalLifecycleEventFactory.completed(
                    reversal, reversal.version(), command.attemptId(),
                    command.providerEvidenceId(), ledgerTransactionId,
                    command.correlationId(), command.messageId(), accepted.appliedAt());
        } else {
            draft = ReversalLifecycleEventFactory.failed(
                    reversal, reversal.version(), command.attemptId(),
                    command.providerEvidenceId(), command.correlationId(),
                    command.messageId(), accepted.appliedAt());
        }
        try {
            return outbox.requireExistingEquivalent(draft);
        } catch (OutboxConsistencyException exception) {
            throw consistency(command, "Accepted Reversal result has missing lifecycle outbox evidence", exception);
        }
    }

    private LockedState lockState(PaymentProviderResultCommand command) {
        VersionedPayment payment = payments.lockByTenantAndId(
                command.tenantId(), PaymentId.from(command.paymentId())
        ).orElseThrow(() -> consistency(command, "Originating Payment does not exist"));
        Reversal reversal = reversals.lockByTenantAndId(
                command.tenantId(), ReversalId.from(command.operationId())
        ).orElseThrow(() -> consistency(command, "Reversal does not exist"));
        if (!reversal.paymentId().equals(payment.payment().id())) {
            throw consistency(command, "Reversal does not belong to the locked Payment");
        }
        return new LockedState(payment.payment(), payment.version(), reversal);
    }

    private void requireEnvelopeMatches(
            IncomingMessage incoming,
            PaymentProviderResultCommand command
    ) {
        if (!CONSUMER_NAME.equals(incoming.consumerName())
                || !incoming.messageId().equals(command.messageId())
                || !command.tenantId().equals(incoming.tenantId())
                || !"ProviderReversalResultObserved".equals(incoming.messageType())
                || command.operationType() != ProviderOperationType.REVERSAL) {
            throw consistency(command, "Reversal result envelope identity is inconsistent");
        }
    }

    private void requireEvidenceMatches(
            PaymentProviderResultCommand command,
            ProviderEvidence evidence
    ) {
        if (!evidence.evidenceId().equals(command.providerEvidenceId())
                || !evidence.tenantId().equals(command.tenantId())
                || !evidence.paymentId().equals(command.paymentId())
                || evidence.operationType() != ProviderOperationType.REVERSAL
                || !evidence.operationId().equals(command.operationId())
                || !evidence.attemptId().equals(command.attemptId())
                || !evidence.providerId().equals(command.providerId())
                || !evidence.providerIdempotencyKey().equals(command.providerIdempotencyKey())
                || !evidence.providerResultId().equals(command.providerResultId())
                || !Objects.equals(evidence.providerReference(), command.providerReference())
                || evidence.category() != command.category()
                || evidence.retryDisposition() != command.retryDisposition()
                || !evidence.evidenceOrigin().equals(command.evidenceOrigin())
                || !evidence.observedAt().equals(command.observedAt())) {
            throw consistency(command, "Provider Reversal result does not match durable evidence");
        }
    }

    private void requireAttemptMatches(
            Payment payment,
            Reversal reversal,
            PaymentAttempt attempt,
            PaymentProviderResultCommand command,
            String originalProviderReference
    ) {
        if (!attempt.tenantId().equals(payment.tenantId())
                || !attempt.paymentId().equals(payment.id())
                || attempt.subjectType() != AttemptSubjectType.REVERSAL
                || !attempt.subjectId().equals(reversal.id().value())
                || attempt.providerId() != ProviderId.SIMULATOR
                || !attempt.providerIdempotencyKey().equals(command.providerIdempotencyKey())
                || !attempt.merchantId().equals(reversal.merchantId())
                || !attempt.customerId().equals(payment.customerId())
                || !attempt.amount().equals(reversal.amount())
                || !attempt.paymentMethodCategory().equals(payment.paymentMethodCategory())
                || !attempt.requestIntentHash().equals(
                RequestIntentHash.calculateReversal(payment, reversal, originalProviderReference))) {
            throw consistency(command, "Provider result references a mismatched Reversal Attempt");
        }
    }

    private void requireProcessing(
            Payment payment,
            Reversal reversal,
            PaymentProviderResultCommand command
    ) {
        if (payment.status() != PaymentStatus.COMPLETED
                || reversal.status() != ReversalStatus.PROCESSING) {
            throw consistency(command, "Provider Reversal result cannot apply in the current state");
        }
    }

    private boolean isFinal(
            ProviderResultCategory category,
            RetryDisposition disposition
    ) {
        return (category == ProviderResultCategory.SUCCESS
                || category == ProviderResultCategory.DECLINED
                || category == ProviderResultCategory.PERMANENT_FAILURE)
                && disposition == RetryDisposition.NOT_RETRYABLE
                || category == ProviderResultCategory.TEMPORARY_FAILURE
                && disposition == RetryDisposition.SAFE_TO_RESUBMIT;
    }

    private void requireDispositionMatches(PaymentProviderResultCommand command) {
        boolean valid = switch (command.category()) {
            case SUCCESS, DECLINED, PERMANENT_FAILURE ->
                    command.retryDisposition() == RetryDisposition.NOT_RETRYABLE;
            case TEMPORARY_FAILURE ->
                    command.retryDisposition() == RetryDisposition.SAFE_TO_RESUBMIT
                            || command.retryDisposition() == RetryDisposition.STATUS_RECOVERY_REQUIRED;
            case ACCEPTED, PENDING, UNKNOWN ->
                    command.retryDisposition() == RetryDisposition.STATUS_RECOVERY_REQUIRED;
        };
        if (!valid) {
            throw consistency(command, "Provider Reversal result category and retry disposition conflict");
        }
    }

    private boolean acceptedMatches(
            PaymentProviderResultCommand command,
            AcceptedFinalReversalResult accepted
    ) {
        return accepted.tenantId().equals(command.tenantId())
                && accepted.reversalId().equals(command.operationId())
                && accepted.paymentId().equals(command.paymentId())
                && accepted.attemptId().equals(command.attemptId())
                && accepted.providerEvidenceId().equals(command.providerEvidenceId())
                && accepted.providerResultId().equals(command.providerResultId())
                && accepted.finalCategory() == command.category()
                && Objects.equals(accepted.providerReference(), command.providerReference());
    }

    private ReversalProviderResultResult result(
            Reversal reversal,
            Payment payment,
            ReversalProviderResultOutcome outcome,
            UUID lifecycleMessageId,
            UUID ledgerTransactionId
    ) {
        return new ReversalProviderResultResult(
                reversal.id().value(), reversal.status(), payment.status(), outcome,
                lifecycleMessageId, ledgerTransactionId
        );
    }

    private ReversalProviderResultConsistencyException consistency(
            PaymentProviderResultCommand command,
            String message
    ) {
        return new ReversalProviderResultConsistencyException(command.operationId(), message);
    }

    private ReversalProviderResultConsistencyException consistency(
            PaymentProviderResultCommand command,
            String message,
            Throwable cause
    ) {
        return new ReversalProviderResultConsistencyException(command.operationId(), message, cause);
    }

    private void auditCompleted(
            PaymentProviderResultCommand command,
            Reversal reversal,
            UUID ledgerTransactionId
    ) {
        audit.appendAction(
                "system", "provider-reversal-result", "SYSTEM", reversal.tenantId(),
                "payment.reversal-completed", "reversal", reversal.id().value().toString(),
                "Reversal completed after Provider success",
                "{\"paymentId\":\"" + reversal.paymentId().value()
                        + "\",\"ledgerTransactionId\":\"" + ledgerTransactionId + "\"}",
                command.correlationId().toString()
        );
    }

    private void auditFailed(
            PaymentProviderResultCommand command,
            Reversal reversal
    ) {
        audit.appendAction(
                "system", "provider-reversal-result", "SYSTEM", reversal.tenantId(),
                "payment.reversal-failed", "reversal", reversal.id().value().toString(),
                "Reversal failed after Provider result",
                "{\"paymentId\":\"" + reversal.paymentId().value()
                        + "\",\"failureCategory\":\"" + reversal.failureCategory() + "\"}",
                command.correlationId().toString()
        );
    }

    private record LockedState(Payment payment, long paymentVersion, Reversal reversal) {
    }
}
