package com.ledgerops.payment.application;

import com.ledgerops.messaging.api.InboxResult;
import com.ledgerops.messaging.api.IncomingMessage;
import com.ledgerops.messaging.api.MessageOutbox;
import com.ledgerops.messaging.api.OutboxConsistencyException;
import com.ledgerops.messaging.api.StoredOutboxMessage;
import com.ledgerops.payment.domain.Payment;
import com.ledgerops.payment.domain.PaymentAttempt;
import com.ledgerops.payment.domain.PaymentAttemptId;
import com.ledgerops.payment.domain.PaymentId;
import com.ledgerops.payment.domain.PaymentStatus;
import com.ledgerops.payment.domain.ProviderId;
import com.ledgerops.provider.api.ProviderEvidence;
import com.ledgerops.provider.api.ProviderEvidenceQuery;
import com.ledgerops.provider.api.ProviderResultCategory;
import com.ledgerops.provider.api.RetryDisposition;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

@Service
public class ApplyPaymentSubmissionRetry {
    public static final String CONSUMER_NAME = "payment-retry-command-consumer-v1";
    private static final int MAXIMUM_ATTEMPTS = 3;

    private final com.ledgerops.messaging.api.ConsumerMessageStore inbox;
    private final ProviderEvidenceQuery evidenceQuery;
    private final PaymentRetryStore paymentStore;
    private final MessageOutbox outbox;
    private final Clock clock;

    public ApplyPaymentSubmissionRetry(
            com.ledgerops.messaging.api.ConsumerMessageStore inbox,
            ProviderEvidenceQuery evidenceQuery,
            PaymentRetryStore paymentStore,
            MessageOutbox outbox,
            Clock clock
    ) {
        this.inbox = inbox;
        this.evidenceQuery = evidenceQuery;
        this.paymentStore = paymentStore;
        this.outbox = outbox;
        this.clock = clock;
    }

    @Transactional
    public PaymentRetryResult apply(
            IncomingMessage incoming,
            PaymentSubmissionRetryCommand command
    ) {
        requireEnvelope(incoming, command);
        InboxResult inboxResult = inbox.recordProcessed(incoming);
        Optional<PaymentRetryApplication> existing = paymentStore.findRetryApplication(
                command.tenantId(), command.retryRequestId());
        if (inboxResult == InboxResult.DUPLICATE || existing.isPresent()) {
            return replay(command, existing.orElseThrow(() -> consistency(
                    "Processed retry command has no Payment retry application")));
        }

        ProviderEvidence evidence = evidenceQuery.find(
                command.tenantId(), command.providerEvidenceId()
        ).orElseThrow(() -> new ProviderEvidenceUnavailableException(
                command.providerEvidenceId()));
        requireSafeEvidence(command, evidence);

        PaymentId paymentId = PaymentId.from(command.paymentId());
        VersionedPayment current = paymentStore.lockByTenantAndId(
                command.tenantId(), paymentId
        ).orElseThrow(() -> new PaymentLifecycleNotFoundException(
                command.tenantId(), paymentId));
        Optional<PaymentRetryApplication> concurrent = paymentStore.findRetryApplication(
                command.tenantId(), command.retryRequestId());
        if (concurrent.isPresent()) {
            return replay(command, concurrent.orElseThrow());
        }
        Payment payment = current.payment();
        if (payment.status() != PaymentStatus.PROCESSING) {
            throw new PaymentLifecycleStateException(
                    payment.id(), PaymentStatus.PROCESSING, payment.status());
        }
        PaymentAttempt previous = paymentStore.findAttemptById(
                command.tenantId(), paymentId, command.previousAttemptId()
        ).orElseThrow(() -> consistency("Previous Payment Attempt does not exist"));
        PaymentAttempt latest = paymentStore.findLatestAttempt(
                command.tenantId(), paymentId
        ).orElseThrow(() -> consistency("PROCESSING Payment has no Payment Attempt"));
        if (!latest.attemptId().equals(previous.attemptId())) {
            throw consistency("Retry does not reference the latest Payment Attempt");
        }
        if (previous.sequence() >= MAXIMUM_ATTEMPTS) {
            throw consistency("Release 0.2 maximum Payment Attempts has been reached");
        }
        requireAttemptMatchesPayment(payment, previous);

        Instant appliedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        PaymentAttempt next = new PaymentAttempt(
                PaymentAttemptId.from(java.util.UUID.randomUUID()), previous.tenantId(), previous.paymentId(),
                previous.sequence() + 1, ProviderId.SIMULATOR,
                previous.providerIdempotencyKey(), appliedAt, previous.merchantId(),
                previous.customerId(), previous.amount(), previous.paymentMethodCategory(),
                previous.requestIntentHash());
        paymentStore.insertAttempt(next);
        paymentStore.insertRetryApplication(new PaymentRetryApplication(
                command.tenantId(), command.retryRequestId(), command.paymentId(),
                command.previousAttemptId(), next.attemptId().value(),
                command.providerEvidenceId(), command.providerId(), command.requestedAt(), appliedAt));
        StoredOutboxMessage message;
        try {
            message = outbox.appendOrGet(PaymentSubmissionMessageFactory.draft(
                    next, command.correlationId(), command.messageId(), appliedAt));
        } catch (OutboxConsistencyException exception) {
            throw consistency("Retry submission command has conflicting content");
        }
        return new PaymentRetryResult(next, message.outboxId(), message.messageId(), false);
    }

    private PaymentRetryResult replay(
            PaymentSubmissionRetryCommand command,
            PaymentRetryApplication application
    ) {
        if (!application.paymentId().equals(command.paymentId())
                || !application.previousAttemptId().equals(command.previousAttemptId())
                || !application.providerEvidenceId().equals(command.providerEvidenceId())
                || !application.providerId().equals(command.providerId())
                || !application.requestedAt().equals(command.requestedAt())) {
            throw consistency("Retry request identity was reused with different content");
        }
        PaymentAttempt attempt = paymentStore.findAttemptById(
                command.tenantId(), PaymentId.from(command.paymentId()), application.newAttemptId()
        ).orElseThrow(() -> consistency("Retry application has no immutable Payment Attempt"));
        StoredOutboxMessage message;
        try {
            message = outbox.requireExistingEquivalent(PaymentSubmissionMessageFactory.draft(
                    attempt, command.correlationId(), command.messageId(), application.appliedAt()));
        } catch (OutboxConsistencyException exception) {
            throw consistency("Retry application has missing or mismatched command evidence");
        }
        return new PaymentRetryResult(attempt, message.outboxId(), message.messageId(), true);
    }

    private void requireSafeEvidence(
            PaymentSubmissionRetryCommand command,
            ProviderEvidence evidence
    ) {
        if (!evidence.tenantId().equals(command.tenantId())
                || !evidence.paymentId().equals(command.paymentId())
                || !evidence.attemptId().equals(command.previousAttemptId())
                || !evidence.evidenceId().equals(command.providerEvidenceId())
                || !evidence.providerId().equals(command.providerId())
                || evidence.category() != ProviderResultCategory.TEMPORARY_FAILURE
                || evidence.retryDisposition() != RetryDisposition.SAFE_TO_RESUBMIT
                || !evidence.noAcceptanceProven()
                || evidence.providerTransactionFound()) {
            throw consistency("Provider evidence does not authorise safe resubmission");
        }
    }

    private void requireAttemptMatchesPayment(Payment payment, PaymentAttempt attempt) {
        if (!attempt.tenantId().equals(payment.tenantId())
                || !attempt.paymentId().equals(payment.id())
                || attempt.providerId() != ProviderId.SIMULATOR
                || !attempt.merchantId().equals(payment.merchantReference().value())
                || !attempt.customerId().equals(payment.customerId())
                || !attempt.amount().equals(payment.amount())
                || !attempt.paymentMethodCategory().equals(payment.paymentMethodCategory())
                || !attempt.requestIntentHash().equals(RequestIntentHash.calculate(payment))) {
            throw consistency("Previous attempt does not match immutable Payment intent");
        }
    }

    private void requireEnvelope(IncomingMessage incoming, PaymentSubmissionRetryCommand command) {
        if (!incoming.consumerName().equals(CONSUMER_NAME)
                || !incoming.messageId().equals(command.messageId())
                || !incoming.tenantId().equals(command.tenantId())
                || !incoming.messageType().equals("PaymentSubmissionRetryRequested")) {
            throw consistency("Retry command envelope does not match its payload");
        }
        if (!"SIMULATOR".equals(command.providerId())) {
            throw consistency("Release 0.2 provider must be SIMULATOR");
        }
    }

    private PaymentRetryConsistencyException consistency(String message) {
        return new PaymentRetryConsistencyException(message);
    }
}
