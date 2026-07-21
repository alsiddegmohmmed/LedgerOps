package com.ledgerops.payment.application;

import com.ledgerops.messaging.api.MessageOutbox;
import com.ledgerops.messaging.api.OutboxConsistencyException;
import com.ledgerops.messaging.api.OutboxMessageDraft;
import com.ledgerops.messaging.api.ProducerName;
import com.ledgerops.messaging.api.StoredOutboxMessage;
import com.ledgerops.payment.domain.Payment;
import com.ledgerops.payment.domain.PaymentAttempt;
import com.ledgerops.payment.domain.PaymentAttemptId;
import com.ledgerops.payment.domain.PaymentStatus;
import com.ledgerops.payment.domain.ProviderId;
import io.micrometer.core.instrument.MeterRegistry;
import com.ledgerops.tenancy.api.TenantActivityQuery;
import com.ledgerops.tenancy.api.TenantActivityStatus;
import com.ledgerops.tenancy.api.TenantReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Optional;
import java.util.UUID;

@Service
public class SubmitApprovedPayment {

    static final String MESSAGE_TYPE = "SubmitPaymentToProvider";
    static final String TOPIC = "ledgerops.provider.commands.v1";
    private static final Logger LOGGER = LoggerFactory.getLogger(SubmitApprovedPayment.class);

    private final PaymentSubmissionStore paymentStore;
    private final MessageOutbox outbox;
    private final Clock clock;
    private final MeterRegistry meterRegistry;
    private final TenantActivityQuery tenantActivityQuery;

    public SubmitApprovedPayment(
            PaymentSubmissionStore paymentStore,
            MessageOutbox outbox,
            Clock clock,
            MeterRegistry meterRegistry,
            TenantActivityQuery tenantActivityQuery
    ) {
        this.paymentStore = paymentStore;
        this.outbox = outbox;
        this.clock = clock;
        this.meterRegistry = meterRegistry;
        this.tenantActivityQuery = tenantActivityQuery;
    }

    @Transactional
    public PaymentSubmissionResult submit(SubmitApprovedPaymentCommand command) {
        TenantActivityStatus tenantStatus = tenantActivityQuery.evaluateForUpdate(
                TenantReference.from(command.tenantId())
        );
        VersionedPayment current = paymentStore.lockByTenantAndId(
                command.tenantId(),
                command.paymentId()
        ).orElseThrow(() -> notFound(command));
        Payment payment = current.payment();
        Optional<PaymentAttempt> existingAttempt = paymentStore.findAttempt(
                payment.tenantId(),
                payment.id(),
                1
        );

        if (payment.status() == PaymentStatus.PROCESSING) {
            return replay(command, current, existingAttempt.orElseThrow(() -> consistency(
                    payment,
                    "PROCESSING Payment has no sequence-1 Payment Attempt",
                    command.correlationId()
            )));
        }
        if (payment.status() != PaymentStatus.APPROVED) {
            LOGGER.warn(
                    "Payment submission lifecycle failure tenantId={} paymentId={} status={} correlationId={}",
                    payment.tenantId(), payment.id().value(), payment.status(),
                    command.correlationId()
            );
            recordOutcome("lifecycle_error");
            throw new PaymentLifecycleStateException(
                    payment.id(),
                    PaymentStatus.APPROVED,
                    payment.status()
            );
        }
        if (tenantStatus != TenantActivityStatus.ALLOWED) {
            LOGGER.warn(
                    "Payment submission tenant unavailable tenantId={} paymentId={} tenantStatus={} correlationId={}",
                    payment.tenantId(), payment.id().value(), tenantStatus,
                    command.correlationId()
            );
            recordOutcome("tenant_unavailable");
            throw new PaymentReferenceUnavailableException(
                    PaymentReferenceType.TENANT,
                    tenantStatus.name()
            );
        }
        if (existingAttempt.isPresent() || outbox.findByAggregate(
                ProducerName.PAYMENT,
                MESSAGE_TYPE,
                payment.tenantId(),
                payment.id().value()
        ).isPresent()) {
            throw consistency(
                    payment,
                    "APPROVED Payment has partial submission evidence",
                    command.correlationId()
            );
        }

        Instant initiatedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        PaymentAttempt attempt = newAttempt(payment, initiatedAt);
        String deduplicationKey = submissionKeyFor(attempt.attemptId().value());
        if (outbox.find(ProducerName.PAYMENT, deduplicationKey).isPresent()) {
            throw consistency(
                    payment,
                    "APPROVED Payment has a pre-existing submission command",
                    command.correlationId()
            );
        }

        try {
            paymentStore.insertAttempt(attempt);
            Payment processing = payment.startProcessing();
            if (!paymentStore.compareAndSet(processing, current.version())) {
                throw new PaymentOptimisticConcurrencyException(payment.id(), current.version());
            }

            StoredOutboxMessage message = outbox.appendOrGet(draft(command, attempt));
            observeAfterCommit("created", () -> LOGGER.info(
                    "Payment submitted durably tenantId={} paymentId={} attemptId={} messageId={} correlationId={}",
                    payment.tenantId(),
                    payment.id().value(),
                    attempt.attemptId().value(),
                    message.messageId(),
                    command.correlationId()
            ));
            return new PaymentSubmissionResult(
                    new VersionedPayment(processing, Math.addExact(current.version(), 1)),
                    attempt,
                    message.outboxId(),
                    message.messageId(),
                    false
            );
        } catch (PaymentOptimisticConcurrencyException exception) {
            LOGGER.warn(
                    "Payment submission concurrency failure tenantId={} paymentId={} correlationId={}",
                    payment.tenantId(), payment.id().value(), command.correlationId()
            );
            recordOutcome("concurrency_error");
            throw exception;
        } catch (OutboxConsistencyException exception) {
            throw consistency(
                    payment,
                    "Submission outbox identity has conflicting content",
                    command.correlationId()
            );
        } catch (DataAccessException exception) {
            LOGGER.error(
                    "Payment submission persistence failure tenantId={} paymentId={} correlationId={}",
                    payment.tenantId(), payment.id().value(), command.correlationId()
            );
            recordOutcome("persistence_error");
            throw new PaymentSubmissionPersistenceException(
                    payment.id(),
                    "Could not persist the atomic Payment submission",
                    exception
            );
        }
    }

    private PaymentSubmissionResult replay(
            SubmitApprovedPaymentCommand command,
            VersionedPayment current,
            PaymentAttempt attempt
    ) {
        Payment payment = current.payment();
        requireAttemptMatches(payment, attempt, command.correlationId());
        StoredOutboxMessage message;
        try {
            message = outbox.requireExistingEquivalent(draft(command, attempt));
        } catch (OutboxConsistencyException exception) {
            throw consistency(
                    payment,
                    "PROCESSING Payment has missing or mismatched submission evidence",
                    command.correlationId()
            );
        } catch (DataAccessException exception) {
            LOGGER.error(
                    "Payment submission replay persistence failure tenantId={} paymentId={} correlationId={}",
                    payment.tenantId(), payment.id().value(), command.correlationId()
            );
            recordOutcome("persistence_error");
            throw new PaymentSubmissionPersistenceException(
                    payment.id(),
                    "Could not read durable Payment submission evidence",
                    exception
            );
        }
        observeAfterCommit("replay", () -> LOGGER.info(
                "Payment submission replayed tenantId={} paymentId={} attemptId={} messageId={} correlationId={}",
                payment.tenantId(),
                payment.id().value(),
                attempt.attemptId().value(),
                message.messageId(),
                command.correlationId()
        ));
        return new PaymentSubmissionResult(
                current,
                attempt,
                message.outboxId(),
                message.messageId(),
                true
        );
    }

    private PaymentAttempt newAttempt(Payment payment, Instant initiatedAt) {
        return new PaymentAttempt(
                PaymentAttemptId.from(UUID.randomUUID()),
                payment.tenantId(),
                payment.id(),
                1,
                ProviderId.SIMULATOR,
                providerIdempotencyKey(payment),
                initiatedAt,
                payment.merchantReference().value(),
                payment.customerId(),
                payment.amount(),
                payment.paymentMethodCategory(),
                RequestIntentHash.calculate(payment)
        );
    }

    private void requireAttemptMatches(
            Payment payment,
            PaymentAttempt attempt,
            UUID correlationId
    ) {
        if (!attempt.tenantId().equals(payment.tenantId())
                || !attempt.paymentId().equals(payment.id())
                || attempt.sequence() != 1
                || attempt.providerId() != ProviderId.SIMULATOR
                || !attempt.providerIdempotencyKey().equals(providerIdempotencyKey(payment))
                || !attempt.merchantId().equals(payment.merchantReference().value())
                || !attempt.customerId().equals(payment.customerId())
                || !attempt.amount().equals(payment.amount())
                || !attempt.paymentMethodCategory().equals(payment.paymentMethodCategory())
                || !attempt.requestIntentHash().equals(RequestIntentHash.calculate(payment))) {
            throw consistency(
                    payment,
                    "PROCESSING Payment has mismatched attempt evidence",
                    correlationId
            );
        }
    }

    private OutboxMessageDraft draft(
            SubmitApprovedPaymentCommand command,
            PaymentAttempt attempt
    ) {
        LinkedHashMap<String, Object> fields = new LinkedHashMap<>();
        fields.put("attemptId", attempt.attemptId().value().toString());
        fields.put("paymentId", attempt.paymentId().value().toString());
        fields.put("attemptSequence", attempt.sequence());
        fields.put("providerId", attempt.providerId().name());
        fields.put("providerIdempotencyKey", attempt.providerIdempotencyKey());
        fields.put("amount", attempt.amount().amount().toPlainString());
        fields.put("currency", attempt.amount().currency().getCurrencyCode());
        fields.put("paymentMethodCategory", attempt.paymentMethodCategory().value());
        fields.put("requestIntentHash", attempt.requestIntentHash());
        String payload = CanonicalJson.object(fields);
        return new OutboxMessageDraft(
                ProducerName.PAYMENT,
                submissionKeyFor(attempt.attemptId().value()),
                MESSAGE_TYPE,
                1,
                attempt.paymentId().value(),
                attempt.tenantId(),
                TOPIC,
                attempt.paymentId().value().toString(),
                payload,
                command.correlationId(),
                command.causationId(),
                attempt.initiatedAt()
        );
    }

    private String providerIdempotencyKey(Payment payment) {
        return "payment:" + payment.id().value().toString().toLowerCase();
    }

    private static String submissionKeyFor(UUID attemptId) {
        return "payment-submission:" + attemptId;
    }

    private PaymentSubmissionConsistencyException consistency(
            Payment payment,
            String message,
            UUID correlationId
    ) {
        LOGGER.error(
                "Payment submission consistency failure tenantId={} paymentId={} detail={} correlationId={}",
                payment.tenantId(),
                payment.id().value(),
                message,
                correlationId
        );
        recordOutcome("consistency_error");
        return new PaymentSubmissionConsistencyException(payment.id(), message);
    }

    private PaymentLifecycleNotFoundException notFound(
            SubmitApprovedPaymentCommand command
    ) {
        LOGGER.warn(
                "Payment submission not found tenantId={} paymentId={} correlationId={}",
                command.tenantId(), command.paymentId().value(), command.correlationId()
        );
        recordOutcome("not_found");
        return new PaymentLifecycleNotFoundException(command.tenantId(), command.paymentId());
    }

    private void recordOutcome(String outcome) {
        meterRegistry.counter("ledgerops.payment.submission", "outcome", outcome).increment();
    }

    private void observeAfterCommit(String outcome, Runnable logAction) {
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        logAction.run();
                        recordOutcome(outcome);
                    }
                }
        );
    }
}
