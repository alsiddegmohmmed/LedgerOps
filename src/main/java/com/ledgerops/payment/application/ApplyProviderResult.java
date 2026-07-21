package com.ledgerops.payment.application;

import com.ledgerops.messaging.api.InboxResult;
import com.ledgerops.messaging.api.IncomingMessage;
import com.ledgerops.messaging.api.MessageOutbox;
import com.ledgerops.messaging.api.OutboxConsistencyException;
import com.ledgerops.messaging.api.OutboxMessageDraft;
import com.ledgerops.messaging.api.ProducerName;
import com.ledgerops.messaging.api.StoredOutboxMessage;
import com.ledgerops.payment.domain.Payment;
import com.ledgerops.payment.domain.PaymentAttempt;
import com.ledgerops.payment.domain.PaymentId;
import com.ledgerops.payment.domain.PaymentStatus;
import com.ledgerops.payment.domain.ProviderId;
import com.ledgerops.provider.api.ProviderEvidence;
import com.ledgerops.provider.api.ProviderEvidenceQuery;
import com.ledgerops.provider.api.ProviderResultCategory;
import com.ledgerops.provider.api.RetryDisposition;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
public class ApplyProviderResult {

    public static final String CONSUMER_NAME = "payment-provider-result-consumer-v1";
    static final String LIFECYCLE_TOPIC = "ledgerops.payment.lifecycle.v1";
    private static final Logger LOGGER = LoggerFactory.getLogger(ApplyProviderResult.class);

    private final com.ledgerops.messaging.api.ConsumerMessageStore inbox;
    private final ProviderEvidenceQuery evidenceQuery;
    private final PaymentProviderResultStore paymentStore;
    private final CompletePaymentAfterProviderSuccess completion;
    private final MessageOutbox outbox;
    private final Clock clock;
    private final MeterRegistry meters;

    public ApplyProviderResult(
            com.ledgerops.messaging.api.ConsumerMessageStore inbox,
            ProviderEvidenceQuery evidenceQuery,
            PaymentProviderResultStore paymentStore,
            CompletePaymentAfterProviderSuccess completion,
            MessageOutbox outbox,
            Clock clock,
            MeterRegistry meters
    ) {
        this.inbox = inbox;
        this.evidenceQuery = evidenceQuery;
        this.paymentStore = paymentStore;
        this.completion = completion;
        this.outbox = outbox;
        this.clock = clock;
        this.meters = meters;
    }

    @Transactional
    public PaymentProviderResultResult apply(
            IncomingMessage incoming,
            PaymentProviderResultCommand command
    ) {
        requireEnvelopeMatches(incoming, command);
        InboxResult inboxResult = inbox.recordProcessed(incoming);
        if (inboxResult == InboxResult.DUPLICATE) {
            VersionedPayment current = lock(command);
            observeAfterCommit("duplicate_message", command, current.payment().status());
            return result(
                    command,
                    current.payment().status(),
                    PaymentProviderResultOutcome.DUPLICATE_MESSAGE,
                    null,
                    null
            );
        }

        ProviderEvidence evidence = evidenceQuery.find(
                command.tenantId(),
                command.providerEvidenceId()
        ).orElseThrow(() -> new ProviderEvidenceUnavailableException(
                command.providerEvidenceId()
        ));
        requireEvidenceMatches(command, evidence);

        VersionedPayment current = lock(command);
        Payment payment = current.payment();
        PaymentAttempt attempt = paymentStore.findAttemptById(
                command.tenantId(),
                new PaymentId(command.paymentId()),
                command.attemptId()
        ).orElseThrow(() -> consistency(command, "Payment Attempt does not exist"));
        requireAttemptMatches(payment, attempt, command);

        Optional<AcceptedFinalProviderResult> accepted =
                paymentStore.findAcceptedFinalResult(
                        command.tenantId(),
                        new PaymentId(command.paymentId())
                );
        if (!isFinal(command.category())) {
            if (accepted.isPresent() || isProviderTerminal(payment.status())) {
                observeAfterCommit("terminal_ignored", command, payment.status());
                return result(
                        command,
                        payment.status(),
                        PaymentProviderResultOutcome.TERMINAL_IGNORED,
                        null,
                        null
                );
            }
            requireProcessing(payment, command);
            observeAfterCommit("non_final", command, payment.status());
            return result(
                    command,
                    payment.status(),
                    PaymentProviderResultOutcome.NON_FINAL,
                    null,
                    null
            );
        }

        requireFinalDisposition(command);
        if (accepted.isPresent()) {
            return replay(command, current, accepted.orElseThrow());
        }
        requireProcessing(payment, command);
        return applyFinal(command, current);
    }

    private PaymentProviderResultResult applyFinal(
            PaymentProviderResultCommand command,
            VersionedPayment current
    ) {
        Instant appliedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        AcceptedFinalProviderResult accepted = accepted(command, appliedAt);

        if (command.category() == ProviderResultCategory.SUCCESS) {
            PaymentCompletionResult completed = completion.complete(
                    command.tenantId(),
                    new PaymentId(command.paymentId())
            );
            paymentStore.insertAcceptedFinalResult(accepted);
            StoredOutboxMessage message = appendLifecycle(
                    command,
                    accepted,
                    completed.ledgerTransactionId(),
                    false
            );
            observeAfterCommit("completed", command, PaymentStatus.COMPLETED);
            return result(
                    command,
                    PaymentStatus.COMPLETED,
                    PaymentProviderResultOutcome.COMPLETED,
                    message.messageId(),
                    completed.ledgerTransactionId()
            );
        }

        Payment failed = current.payment().fail();
        if (!paymentStore.compareAndSet(failed, current.version())) {
            throw new PaymentOptimisticConcurrencyException(
                    current.payment().id(),
                    current.version()
            );
        }
        paymentStore.insertAcceptedFinalResult(accepted);
        StoredOutboxMessage message = appendLifecycle(command, accepted, null, false);
        observeAfterCommit("failed", command, PaymentStatus.FAILED);
        return result(
                command,
                PaymentStatus.FAILED,
                PaymentProviderResultOutcome.FAILED,
                message.messageId(),
                null
        );
    }

    private PaymentProviderResultResult replay(
            PaymentProviderResultCommand command,
            VersionedPayment current,
            AcceptedFinalProviderResult accepted
    ) {
        requireAcceptedMatches(command, accepted);
        UUID ledgerTransactionId = null;
        if (accepted.finalCategory() == ProviderResultCategory.SUCCESS) {
            if (current.payment().status() != PaymentStatus.COMPLETED) {
                throw consistency(command, "Accepted SUCCESS does not match Payment status");
            }
            ledgerTransactionId = completion.complete(
                    command.tenantId(),
                    new PaymentId(command.paymentId())
            ).ledgerTransactionId();
        } else if (current.payment().status() != PaymentStatus.FAILED) {
            throw consistency(command, "Accepted failure does not match Payment status");
        }

        StoredOutboxMessage message = appendLifecycle(
                command,
                accepted,
                ledgerTransactionId,
                true
        );
        observeAfterCommit("replay", command, current.payment().status());
        return result(
                command,
                current.payment().status(),
                PaymentProviderResultOutcome.REPLAY,
                message.messageId(),
                ledgerTransactionId
        );
    }

    private StoredOutboxMessage appendLifecycle(
            PaymentProviderResultCommand command,
            AcceptedFinalProviderResult accepted,
            UUID ledgerTransactionId,
            boolean requireExisting
    ) {
        OutboxMessageDraft draft = lifecycleDraft(
                command,
                accepted,
                ledgerTransactionId
        );
        try {
            return requireExisting
                    ? outbox.requireExistingEquivalent(draft)
                    : outbox.appendOrGet(draft);
        } catch (OutboxConsistencyException exception) {
            throw new PaymentProviderResultConsistencyException(
                    command.paymentId(),
                    "Payment lifecycle outbox evidence is missing or inconsistent",
                    exception
            );
        }
    }

    private OutboxMessageDraft lifecycleDraft(
            PaymentProviderResultCommand command,
            AcceptedFinalProviderResult accepted,
            UUID ledgerTransactionId
    ) {
        boolean success = accepted.finalCategory() == ProviderResultCategory.SUCCESS;
        String messageType = success ? "PaymentCompleted" : "PaymentFailed";
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("paymentId", accepted.paymentId().toString());
        payload.put("attemptId", accepted.attemptId().toString());
        payload.put("providerEvidenceId", accepted.providerEvidenceId().toString());
        if (accepted.providerReference() != null) {
            payload.put("providerReference", accepted.providerReference());
        }
        if (success) {
            payload.put("ledgerTransactionId", Objects.requireNonNull(
                    ledgerTransactionId,
                    "Ledger transaction ID is required for PaymentCompleted"
            ).toString());
            payload.put("completedAt", accepted.appliedAt().toString());
        } else {
            payload.put("finalCategory", accepted.finalCategory().name());
            payload.put("failedAt", accepted.appliedAt().toString());
        }
        return new OutboxMessageDraft(
                ProducerName.PAYMENT,
                "payment-final:" + accepted.paymentId(),
                messageType,
                1,
                accepted.paymentId(),
                accepted.tenantId(),
                LIFECYCLE_TOPIC,
                accepted.paymentId().toString(),
                CanonicalJson.object(payload),
                command.correlationId(),
                command.messageId(),
                accepted.appliedAt()
        );
    }

    private void requireEnvelopeMatches(
            IncomingMessage incoming,
            PaymentProviderResultCommand command
    ) {
        if (!CONSUMER_NAME.equals(incoming.consumerName())
                || !incoming.messageId().equals(command.messageId())
                || !command.tenantId().equals(incoming.tenantId())
                || !"ProviderResultObserved".equals(incoming.messageType())) {
            throw consistency(command, "Consumer envelope identity is inconsistent");
        }
    }

    private void requireEvidenceMatches(
            PaymentProviderResultCommand command,
            ProviderEvidence evidence
    ) {
        if (!evidence.evidenceId().equals(command.providerEvidenceId())
                || !evidence.tenantId().equals(command.tenantId())
                || !evidence.paymentId().equals(command.paymentId())
                || !evidence.attemptId().equals(command.attemptId())
                || !evidence.providerId().equals(command.providerId())
                || !evidence.providerIdempotencyKey().equals(
                        command.providerIdempotencyKey()
                )
                || !evidence.providerResultId().equals(command.providerResultId())
                || !Objects.equals(evidence.providerReference(), command.providerReference())
                || evidence.category() != command.category()
                || evidence.retryDisposition() != command.retryDisposition()
                || !evidence.evidenceOrigin().equals(command.evidenceOrigin())
                || !evidence.observedAt().equals(command.observedAt())) {
            throw consistency(command, "Provider result event does not match durable evidence");
        }
    }

    private void requireAttemptMatches(
            Payment payment,
            PaymentAttempt attempt,
            PaymentProviderResultCommand command
    ) {
        if (!attempt.tenantId().equals(payment.tenantId())
                || !attempt.paymentId().equals(payment.id())
                || !attempt.attemptId().value().equals(command.attemptId())
                || attempt.providerId() != ProviderId.SIMULATOR
                || !attempt.providerIdempotencyKey().equals(
                        command.providerIdempotencyKey()
                )
                || !attempt.merchantId().equals(payment.merchantReference().value())
                || !attempt.customerId().equals(payment.customerId())
                || !attempt.amount().equals(payment.amount())
                || !attempt.paymentMethodCategory().equals(payment.paymentMethodCategory())
                || !attempt.requestIntentHash().equals(RequestIntentHash.calculate(payment))) {
            throw consistency(command, "Provider result references a mismatched Payment Attempt");
        }
    }

    private void requireAcceptedMatches(
            PaymentProviderResultCommand command,
            AcceptedFinalProviderResult accepted
    ) {
        if (!accepted.tenantId().equals(command.tenantId())
                || !accepted.paymentId().equals(command.paymentId())
                || !accepted.attemptId().equals(command.attemptId())
                || !accepted.providerEvidenceId().equals(command.providerEvidenceId())
                || !accepted.providerResultId().equals(command.providerResultId())
                || accepted.finalCategory() != command.category()
                || !Objects.equals(accepted.providerReference(), command.providerReference())) {
            throw consistency(command, "Provider final result conflicts with accepted evidence");
        }
    }

    private AcceptedFinalProviderResult accepted(
            PaymentProviderResultCommand command,
            Instant appliedAt
    ) {
        return new AcceptedFinalProviderResult(
                command.tenantId(),
                command.paymentId(),
                command.attemptId(),
                command.providerEvidenceId(),
                command.providerResultId(),
                command.category(),
                command.providerReference(),
                appliedAt
        );
    }

    private VersionedPayment lock(PaymentProviderResultCommand command) {
        return paymentStore.lockByTenantAndId(
                command.tenantId(),
                new PaymentId(command.paymentId())
        ).orElseThrow(() -> new PaymentLifecycleNotFoundException(
                command.tenantId(),
                new PaymentId(command.paymentId())
        ));
    }

    private void requireProcessing(
            Payment payment,
            PaymentProviderResultCommand command
    ) {
        if (payment.status() != PaymentStatus.PROCESSING) {
            throw new PaymentProviderResultConsistencyException(
                    command.paymentId(),
                    "Provider result cannot apply while Payment is " + payment.status()
            );
        }
    }

    private void requireFinalDisposition(PaymentProviderResultCommand command) {
        if (command.retryDisposition() != RetryDisposition.NOT_RETRYABLE) {
            throw consistency(command, "Final Provider result must be NOT_RETRYABLE");
        }
    }

    private boolean isFinal(ProviderResultCategory category) {
        return category == ProviderResultCategory.SUCCESS
                || category == ProviderResultCategory.DECLINED
                || category == ProviderResultCategory.PERMANENT_FAILURE;
    }

    private boolean isProviderTerminal(PaymentStatus status) {
        return status == PaymentStatus.COMPLETED
                || status == PaymentStatus.FAILED
                || status == PaymentStatus.REVERSED
                || status == PaymentStatus.REJECTED;
    }

    private PaymentProviderResultResult result(
            PaymentProviderResultCommand command,
            PaymentStatus status,
            PaymentProviderResultOutcome outcome,
            UUID lifecycleMessageId,
            UUID ledgerTransactionId
    ) {
        return new PaymentProviderResultResult(
                command.paymentId(),
                status,
                outcome,
                lifecycleMessageId,
                ledgerTransactionId
        );
    }

    private PaymentProviderResultConsistencyException consistency(
            PaymentProviderResultCommand command,
            String message
    ) {
        LOGGER.error(
                "Provider result consistency failure tenantId={} paymentId={} evidenceId={} detail={} correlationId={}",
                command.tenantId(), command.paymentId(), command.providerEvidenceId(),
                message, command.correlationId()
        );
        return new PaymentProviderResultConsistencyException(command.paymentId(), message);
    }

    private void observeAfterCommit(
            String outcome,
            PaymentProviderResultCommand command,
            PaymentStatus status
    ) {
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        meters.counter(
                                "ledgerops.payment.provider_result",
                                "outcome", outcome,
                                "category", command.category().name()
                        ).increment();
                        LOGGER.info(
                                "Provider result applied tenantId={} paymentId={} evidenceId={} category={} status={} outcome={} correlationId={}",
                                command.tenantId(), command.paymentId(),
                                command.providerEvidenceId(), command.category(), status,
                                outcome, command.correlationId()
                        );
                    }
                }
        );
    }
}
