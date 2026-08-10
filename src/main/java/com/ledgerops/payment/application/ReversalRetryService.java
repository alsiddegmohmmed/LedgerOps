package com.ledgerops.payment.application;

import com.ledgerops.audit.api.AuditAppendPort;
import com.ledgerops.identity.api.AuthenticatedPrincipal;
import com.ledgerops.identity.api.AuthorizationPermissionDeniedException;
import com.ledgerops.identity.api.AuthorizationResourceNotFoundException;
import com.ledgerops.identity.api.AuthorizedRequestContext;
import com.ledgerops.messaging.api.MessageOutbox;
import com.ledgerops.messaging.api.OutboxConsistencyException;
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
import com.ledgerops.provider.api.ProviderEvidence;
import com.ledgerops.provider.api.ProviderEvidenceQuery;
import com.ledgerops.provider.api.ProviderOperationType;
import com.ledgerops.provider.api.ProviderResultCategory;
import com.ledgerops.provider.api.RetryDisposition;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

@Service
public class ReversalRetryService {

    private static final int MAXIMUM_ATTEMPTS = 3;

    private final PaymentCompletionStore payments;
    private final PaymentProviderResultStore providerResults;
    private final ReversalRetryStore reversals;
    private final ProviderEvidenceQuery evidenceQuery;
    private final MessageOutbox outbox;
    private final AuditAppendPort audit;
    private final Clock clock;

    public ReversalRetryService(
            PaymentCompletionStore payments,
            PaymentProviderResultStore providerResults,
            ReversalRetryStore reversals,
            ProviderEvidenceQuery evidenceQuery,
            MessageOutbox outbox,
            AuditAppendPort audit,
            Clock clock
    ) {
        this.payments = payments;
        this.providerResults = providerResults;
        this.reversals = reversals;
        this.evidenceQuery = evidenceQuery;
        this.outbox = outbox;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public ReversalRetryResult retry(ReversalRetryCommand command) {
        authorize(command);
        if (!command.confirmation()) {
            throw new IllegalArgumentException("Reversal retry requires explicit confirmation");
        }
        String reason = requireReason(command.reason());

        VersionedPayment currentPayment = payments.lockByTenantAndId(
                        command.tenantId(), PaymentId.from(command.paymentId()))
                .orElseThrow(() -> consistency("Originating Payment does not exist"));
        Payment payment = currentPayment.payment();
        Reversal reversal = reversals.lockByTenantAndId(
                        command.tenantId(), ReversalId.from(command.reversalId()))
                .orElseThrow(() -> consistency("Reversal does not exist"));
        if (!reversal.paymentId().equals(payment.id())) {
            throw consistency("Reversal does not belong to the locked Payment");
        }
        if (!command.authorization().allowsMerchant(reversal.merchantId())) {
            throw new AuthorizationResourceNotFoundException();
        }

        Optional<ReversalRetryApplication> existing = reversals.findRetryApplication(
                command.tenantId(), reversal.id(), command.previousAttemptId());
        if (existing.isPresent()) {
            return replay(command, payment, reversal, reason, existing.orElseThrow());
        }

        if (payment.status() != PaymentStatus.COMPLETED) {
            throw consistency("Only a COMPLETED Payment can retry a Reversal");
        }
        if (reversal.status() != ReversalStatus.FAILED) {
            throw consistency("Only a FAILED Reversal can be safely retried");
        }
        AcceptedFinalProviderResult accepted = providerResults.findAcceptedFinalResult(
                        payment.tenantId(), payment.id())
                .filter(result -> result.finalCategory() == ProviderResultCategory.SUCCESS)
                .orElseThrow(() -> consistency(
                        "COMPLETED Payment has no accepted successful Provider result"));
        String originalProviderReference = accepted.providerReference();
        if (originalProviderReference == null || originalProviderReference.isBlank()) {
            throw consistency("Accepted Provider result has no original Provider reference");
        }

        PaymentAttempt previous = reversals.findAttemptById(
                        command.tenantId(), payment.id(), command.previousAttemptId())
                .orElseThrow(() -> consistency("Previous Reversal Attempt does not exist"));
        PaymentAttempt latest = reversals.findLatestReversalAttempt(
                        command.tenantId(), payment.id(), reversal.id())
                .orElseThrow(() -> consistency("FAILED Reversal has no Reversal Attempt"));
        if (!latest.attemptId().equals(previous.attemptId())) {
            throw consistency("Retry does not reference the latest Reversal Attempt");
        }
        if (previous.sequence() >= MAXIMUM_ATTEMPTS) {
            throw consistency("Reversal has reached the maximum of three attempts");
        }
        requireAttemptMatches(payment, reversal, previous, originalProviderReference);
        ProviderEvidence evidence = evidenceQuery.find(
                        command.tenantId(), command.providerEvidenceId())
                .orElseThrow(() -> consistency("Provider retry evidence does not exist"));
        requireSafeEvidence(command, reversal, previous, evidence);

        Instant appliedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        PaymentAttempt next = new PaymentAttempt(
                PaymentAttemptId.from(UUID.randomUUID()),
                previous.tenantId(),
                previous.paymentId(),
                AttemptSubjectType.REVERSAL,
                reversal.id().value(),
                previous.sequence() + 1,
                previous.providerId(),
                previous.providerIdempotencyKey(),
                appliedAt,
                previous.merchantId(),
                previous.customerId(),
                previous.amount(),
                previous.paymentMethodCategory(),
                previous.requestIntentHash()
        );
        Reversal processing = reversal.startSafeRetry(appliedAt);
        reversals.insertAttempt(next);
        if (!reversals.compareAndSet(processing, reversal.version())) {
            throw consistency("Reversal changed while starting safe retry");
        }
        reversals.insertRetryApplication(new ReversalRetryApplication(
                command.tenantId(), reversal.id().value(), payment.id().value(),
                previous.attemptId().value(), next.attemptId().value(),
                evidence.evidenceId(), command.providerId(), reason,
                appliedAt, appliedAt));
        StoredOutboxMessage message = appendSubmission(
                next, reversal, originalProviderReference, command);
        outbox.appendOrGet(ReversalLifecycleEventFactory.processingStarted(
                processing,
                reversal.version() + 1,
                next.attemptId().value(),
                correlationId(command.authorization().correlationId()),
                previous.attemptId().value(),
                appliedAt
        ));
        auditRetry(command.actor(), command.authorization(), reversal, previous, next, reason);
        return new ReversalRetryResult(next, message.outboxId(), message.messageId(), false);
    }

    private ReversalRetryResult replay(
            ReversalRetryCommand command,
            Payment payment,
            Reversal reversal,
            String reason,
            ReversalRetryApplication application
    ) {
        if (!application.paymentId().equals(command.paymentId())
                || !application.previousAttemptId().equals(command.previousAttemptId())
                || !application.providerEvidenceId().equals(command.providerEvidenceId())
                || !application.providerId().equals(command.providerId())
                || !application.requestReason().equals(reason)) {
            throw consistency("Reversal retry identity was reused with different content");
        }
        AcceptedFinalProviderResult accepted = providerResults.findAcceptedFinalResult(
                        payment.tenantId(), payment.id())
                .filter(result -> result.finalCategory() == ProviderResultCategory.SUCCESS)
                .orElseThrow(() -> consistency(
                        "Reversal retry has no accepted original Provider result"));
        PaymentAttempt attempt = reversals.findAttemptById(
                        command.tenantId(), payment.id(), application.newAttemptId())
                .orElseThrow(() -> consistency("Reversal retry has no immutable new attempt"));
        if (attempt.subjectType() != AttemptSubjectType.REVERSAL
                || !attempt.subjectId().equals(reversal.id().value())) {
            throw consistency("Reversal retry new attempt has the wrong subject");
        }
        requireAttemptMatches(payment, reversal, attempt, accepted.providerReference());
        StoredOutboxMessage message;
        try {
            message = outbox.requireExistingEquivalent(ReversalSubmissionMessageFactory.draft(
                    attempt, reversal, accepted.providerReference(),
                    correlationId(command.authorization().correlationId()),
                    reversal.id().value(), application.appliedAt()));
        } catch (OutboxConsistencyException exception) {
            throw new ReversalRetryConsistencyException(
                    "Reversal retry has missing or mismatched provider command", exception);
        }
        return new ReversalRetryResult(attempt, message.outboxId(), message.messageId(), true);
    }

    private StoredOutboxMessage appendSubmission(
            PaymentAttempt attempt,
            Reversal reversal,
            String originalProviderReference,
            ReversalRetryCommand command
    ) {
        try {
            return outbox.appendOrGet(ReversalSubmissionMessageFactory.draft(
                    attempt,
                    reversal,
                    originalProviderReference,
                    correlationId(command.authorization().correlationId()),
                    command.previousAttemptId(),
                    attempt.initiatedAt()
            ));
        } catch (OutboxConsistencyException exception) {
            throw new ReversalRetryConsistencyException(
                    "Reversal retry command has conflicting content", exception);
        }
    }

    private void requireSafeEvidence(
            ReversalRetryCommand command,
            Reversal reversal,
            PaymentAttempt previous,
            ProviderEvidence evidence
    ) {
        if (!evidence.tenantId().equals(command.tenantId())
                || !evidence.paymentId().equals(command.paymentId())
                || evidence.operationType() != ProviderOperationType.REVERSAL
                || !evidence.operationId().equals(reversal.id().value())
                || !evidence.attemptId().equals(previous.attemptId().value())
                || !evidence.evidenceId().equals(command.providerEvidenceId())
                || !evidence.providerId().equals(command.providerId())
                || !previous.providerId().name().equals(command.providerId())
                || !evidence.providerIdempotencyKey().equals(previous.providerIdempotencyKey())
                || evidence.category() != ProviderResultCategory.TEMPORARY_FAILURE
                || evidence.retryDisposition() != RetryDisposition.SAFE_TO_RESUBMIT
                || !evidence.noAcceptanceProven()
                || evidence.providerTransactionFound()) {
            throw consistency("Provider evidence does not authorise Reversal resubmission");
        }
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
                || attempt.providerId() != ProviderId.SIMULATOR
                || !attempt.providerIdempotencyKey().equals(
                "reversal:" + reversal.id().value().toString().toLowerCase(java.util.Locale.ROOT))
                || !attempt.merchantId().equals(reversal.merchantId())
                || !attempt.customerId().equals(payment.customerId())
                || !attempt.amount().equals(reversal.amount())
                || !attempt.paymentMethodCategory().equals(payment.paymentMethodCategory())
                || !attempt.requestIntentHash().equals(
                RequestIntentHash.calculateReversal(payment, reversal, originalProviderReference))) {
            throw consistency("Previous Reversal Attempt does not match immutable Reversal intent");
        }
    }

    private void authorize(ReversalRetryCommand command) {
        AuthorizedRequestContext authorization = command.authorization();
        if (!command.tenantId().equals(authorization.tenantId())) {
            throw new AuthorizationResourceNotFoundException();
        }
        if (!authorization.canRetryReversals()) {
            throw new AuthorizationPermissionDeniedException("reversal:retry");
        }
        if (!"SIMULATOR".equals(command.providerId())) {
            throw consistency("Release 0.3 supports only the SIMULATOR Provider");
        }
    }

    private String requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Reversal retry reason must not be blank");
        }
        return reason.trim();
    }

    private void auditRetry(
            AuthenticatedPrincipal actor,
            AuthorizedRequestContext authorization,
            Reversal reversal,
            PaymentAttempt previous,
            PaymentAttempt next,
            String reason
    ) {
        audit.appendAction(
                actor.issuer(), actor.subject(), actor.principalType(), reversal.tenantId(),
                "payment.reversal-retry-requested", "reversal",
                reversal.id().value().toString(), reason,
                "{\"paymentId\":\"" + reversal.paymentId().value()
                        + "\",\"previousAttemptId\":\"" + previous.attemptId().value()
                        + "\",\"attemptId\":\"" + next.attemptId().value() + "\"}",
                authorization.correlationId());
    }

    private UUID correlationId(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
        }
    }

    private ReversalRetryConsistencyException consistency(String message) {
        return new ReversalRetryConsistencyException(message);
    }
}
