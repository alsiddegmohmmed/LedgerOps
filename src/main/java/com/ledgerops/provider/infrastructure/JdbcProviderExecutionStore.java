package com.ledgerops.provider.infrastructure;

import com.ledgerops.messaging.api.MessageOutbox;
import com.ledgerops.messaging.api.OutboxMessageDraft;
import com.ledgerops.messaging.api.ProducerName;
import com.ledgerops.provider.api.ProviderEvidence;
import com.ledgerops.provider.api.ProviderEvidenceQuery;
import com.ledgerops.provider.api.ProviderResultCategory;
import com.ledgerops.provider.api.RetryDisposition;
import com.ledgerops.provider.application.ProviderCallResult;
import com.ledgerops.provider.application.ProviderExecutionStore;
import com.ledgerops.provider.application.ProviderRetryRequestClaim;
import com.ledgerops.provider.application.ProviderWorkClaim;
import com.ledgerops.provider.application.ProviderWorkConsistencyException;
import com.ledgerops.provider.application.ProviderWorkType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

@Component
class JdbcProviderExecutionStore implements ProviderExecutionStore, ProviderEvidenceQuery {
    private static final Duration LEASE = Duration.ofSeconds(30);
    private final JdbcTemplate jdbc;
    private final MessageOutbox outbox;
    private final Clock clock;

    JdbcProviderExecutionStore(JdbcTemplate jdbc, MessageOutbox outbox, Clock clock) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<ProviderWorkClaim> claimNext(String leaseOwner) {
        Instant now = clock.instant();
        Instant expires = now.plus(LEASE);
        UUID token = UUID.randomUUID();
        return jdbc.query("""
                WITH activated AS (
                    UPDATE provider.work
                       SET status = 'RETRYABLE', updated_at = ?
                     WHERE work_type = 'STATUS_QUERY' AND status = 'WAITING_STATUS'
                       AND due_at <= ?
                    RETURNING id
                ), candidate AS (
                    SELECT id, status AS prior_status, execution_count AS prior_count
                      FROM provider.work
                     WHERE ((status IN ('PENDING', 'RETRYABLE') AND due_at <= ?)
                            OR (status = 'CLAIMED' AND lease_expires_at <= ?))
                       AND work_type IN ('SUBMISSION', 'STATUS_QUERY')
                     ORDER BY due_at, created_at
                     FOR UPDATE SKIP LOCKED
                     LIMIT 1
                ), claimed AS (
                    UPDATE provider.work w
                       SET status = 'CLAIMED', lease_owner = ?, lease_token = ?,
                           lease_expires_at = ?,
                           execution_count = execution_count + CASE
                               WHEN w.work_type = 'SUBMISSION'
                                    AND candidate.prior_status = 'CLAIMED'
                                    AND candidate.prior_count > 0 THEN 0
                               WHEN w.work_type = 'STATUS_QUERY'
                                    AND candidate.prior_count >= 12 THEN 0 ELSE 1 END,
                           updated_at = ?
                      FROM candidate
                     WHERE w.id = candidate.id
                    RETURNING w.*, candidate.prior_status, candidate.prior_count
                )
                SELECT *, (work_type = 'SUBMISSION' AND prior_status = 'CLAIMED'
                            AND prior_count > 0) AS recovery_only,
                          (work_type = 'STATUS_QUERY' AND prior_count >= 12) AS exhausted
                  FROM claimed
                """, rs -> rs.next() ? Optional.of(new ProviderWorkClaim(
                        rs.getObject("id", UUID.class),
                        rs.getObject("tenant_id", UUID.class),
                        rs.getObject("attempt_id", UUID.class),
                        rs.getObject("payment_id", UUID.class),
                        rs.getInt("attempt_sequence"),
                        ProviderWorkType.valueOf(rs.getString("work_type")),
                        rs.getString("provider_id"),
                        rs.getString("provider_idempotency_key"),
                        rs.getString("request_intent_hash"),
                        rs.getString("command_payload"),
                        rs.getObject("correlation_id", UUID.class),
                        rs.getObject("causation_id", UUID.class),
                        rs.getObject("lease_token", UUID.class),
                        rs.getTimestamp("lease_expires_at").toInstant(),
                        rs.getInt("transport_retry_count") < 1,
                        rs.getBoolean("recovery_only"),
                        rs.getBoolean("exhausted")
                )) : Optional.empty(), Timestamp.from(now), Timestamp.from(now),
                Timestamp.from(now), Timestamp.from(now), leaseOwner,
                token, Timestamp.from(expires), Timestamp.from(now));
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<ProviderRetryRequestClaim> claimRetryRequest(String leaseOwner) {
        Instant now = clock.instant();
        UUID token = UUID.randomUUID();
        Instant expires = now.plus(LEASE);
        return jdbc.query("""
                WITH candidate AS (
                    SELECT w.id
                      FROM provider.work w
                     WHERE w.work_type = 'SUBMISSION'
                       AND ((w.status = 'WAITING_RETRY_REQUEST' AND w.due_at <= ?)
                            OR (w.status = 'CLAIMED' AND w.lease_expires_at <= ?
                                AND EXISTS (
                                    SELECT 1 FROM provider.results r
                                     WHERE r.tenant_id = w.tenant_id
                                       AND r.attempt_id = w.attempt_id
                                       AND r.retry_disposition = 'SAFE_TO_RESUBMIT'
                                )))
                     ORDER BY w.due_at, w.created_at
                     FOR UPDATE SKIP LOCKED
                     LIMIT 1
                ), claimed AS (
                    UPDATE provider.work w
                       SET status = 'CLAIMED', lease_owner = ?, lease_token = ?,
                           lease_expires_at = ?, updated_at = ?
                      FROM candidate
                     WHERE w.id = candidate.id
                    RETURNING w.*
                )
                SELECT c.*, r.evidence_id, r.provider_result_id
                  FROM claimed c
                  JOIN LATERAL (
                      SELECT evidence_id, provider_result_id
                        FROM provider.results
                       WHERE tenant_id = c.tenant_id AND attempt_id = c.attempt_id
                         AND retry_disposition = 'SAFE_TO_RESUBMIT'
                       ORDER BY observed_at DESC, evidence_id
                       LIMIT 1
                  ) r ON TRUE
                """, rs -> rs.next() ? Optional.of(new ProviderRetryRequestClaim(
                        rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                        rs.getObject("payment_id", UUID.class),
                        rs.getObject("attempt_id", UUID.class), rs.getInt("attempt_sequence"),
                        rs.getObject("evidence_id", UUID.class),
                        rs.getObject("provider_result_id", UUID.class), rs.getString("provider_id"),
                        rs.getObject("correlation_id", UUID.class),
                        rs.getObject("lease_token", UUID.class),
                        rs.getTimestamp("lease_expires_at").toInstant()
                )) : Optional.empty(), Timestamp.from(now), Timestamp.from(now), leaseOwner,
                token, Timestamp.from(expires), Timestamp.from(now));
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void issueRetryRequest(ProviderRetryRequestClaim claim) {
        Instant now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        requireCurrentLease(claim.workId(), claim.leaseToken(), now);
        RetryRequest existing = jdbc.query("""
                SELECT retry_request_id, requested_at
                  FROM provider.retry_requests
                 WHERE tenant_id = ? AND provider_evidence_id = ?
                """, rs -> rs.next() ? new RetryRequest(
                        rs.getObject(1, UUID.class), rs.getTimestamp(2).toInstant()) : null,
                claim.tenantId(), claim.providerEvidenceId());
        RetryRequest retry = existing;
        if (retry == null) {
            retry = new RetryRequest(UUID.randomUUID(), now);
            jdbc.update("""
                    INSERT INTO provider.retry_requests
                        (retry_request_id, tenant_id, payment_id, previous_attempt_id,
                         provider_evidence_id, provider_id, requested_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, retry.retryRequestId(), claim.tenantId(), claim.paymentId(),
                    claim.previousAttemptId(), claim.providerEvidenceId(), claim.providerId(),
                    Timestamp.from(retry.requestedAt()));
        }
        String payload = "{" +
                "\"paymentId\":\"" + claim.paymentId() + "\"," +
                "\"previousAttemptId\":\"" + claim.previousAttemptId() + "\"," +
                "\"providerEvidenceId\":\"" + claim.providerEvidenceId() + "\"," +
                "\"providerId\":\"SIMULATOR\"," +
                "\"requestedAt\":\"" + retry.requestedAt() + "\"," +
                "\"retryRequestId\":\"" + retry.retryRequestId() + "\"}";
        var resultMessage = outbox.find(
                ProducerName.PROVIDER,
                "provider-result:" + claim.tenantId() + ":SIMULATOR:" + claim.providerResultId()
        ).orElseThrow(() -> new ProviderWorkConsistencyException(
                "Safe retry evidence has no durable Provider-result outbox record"));
        outbox.appendOrGet(new OutboxMessageDraft(
                ProducerName.PROVIDER, "payment-retry:" + retry.retryRequestId(),
                "PaymentSubmissionRetryRequested", 1, claim.paymentId(), claim.tenantId(),
                "ledgerops.payment.commands.v1", claim.paymentId().toString(), payload,
                claim.correlationId(), resultMessage.messageId(), retry.requestedAt()));
        int updated = jdbc.update("""
                UPDATE provider.work
                   SET status = 'COMPLETED', lease_owner = NULL, lease_token = NULL,
                       lease_expires_at = NULL, updated_at = ?
                 WHERE id = ? AND status = 'CLAIMED' AND lease_token = ?
                   AND lease_expires_at > ?
                """, Timestamp.from(now), claim.workId(), claim.leaseToken(), Timestamp.from(now));
        if (updated != 1) {
            throw new ProviderWorkConsistencyException(
                    "Provider retry-request lease was lost before completion");
        }
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean renew(UUID workId, UUID leaseToken) {
        Instant now = clock.instant();
        return jdbc.update("""
                UPDATE provider.work
                   SET lease_expires_at = ?, updated_at = ?
                 WHERE id = ? AND status = 'CLAIMED' AND lease_token = ?
                   AND lease_expires_at > ?
                """, Timestamp.from(now.plus(LEASE)), Timestamp.from(now), workId,
                leaseToken, Timestamp.from(now)) == 1;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void defer(ProviderWorkClaim claim, String reasonCode) {
        Instant now = clock.instant();
        boolean preTransmission = "PRETRANSMISSION_FAILURE".equals(reasonCode);
        Instant dueAt = preTransmission
                ? now.plus(ProviderSchedule.preTransmissionDelay(claim.workId()))
                : now.plusSeconds(1);
        int updated = jdbc.update("""
                UPDATE provider.work
                   SET status = 'RETRYABLE', due_at = ?, lease_owner = NULL,
                       lease_token = NULL, lease_expires_at = NULL,
                       execution_count = execution_count - 1,
                       transport_retry_count = transport_retry_count + ?,
                       last_error_code = ?, updated_at = ?
                 WHERE id = ? AND status = 'CLAIMED' AND lease_token = ?
                   AND lease_expires_at > ?
                   AND (? = false OR transport_retry_count < 1)
                """, Timestamp.from(dueAt), preTransmission ? 1 : 0, reasonCode,
                Timestamp.from(now), claim.workId(), claim.leaseToken(), Timestamp.from(now),
                preTransmission);
        if (updated != 1) {
            throw new ProviderWorkConsistencyException("Provider work lease was lost while deferring");
        }
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markUnresolved(ProviderWorkClaim claim, String reasonCode) {
        Instant now = clock.instant();
        if (claim.workType() == ProviderWorkType.STATUS_QUERY) {
            recordOperationalEvent(claim, "STATUS_RECOVERY_EXHAUSTED", reasonCode);
            jdbc.update("""
                    UPDATE provider.work
                       SET status = 'UNRESOLVED', lease_owner = NULL, lease_token = NULL,
                           lease_expires_at = NULL, last_error_code = ?, updated_at = ?
                     WHERE tenant_id = ? AND attempt_id = ? AND work_type = 'SUBMISSION'
                       AND status = 'WAITING_STATUS'
                    """, reasonCode, Timestamp.from(now), claim.tenantId(), claim.attemptId());
        }
        int updated = jdbc.update("""
                UPDATE provider.work
                   SET status = 'UNRESOLVED', lease_owner = NULL, lease_token = NULL,
                       lease_expires_at = NULL, last_error_code = ?, updated_at = ?
                 WHERE id = ? AND status = 'CLAIMED' AND lease_token = ?
                   AND lease_expires_at > ?
                """, reasonCode, Timestamp.from(now), claim.workId(), claim.leaseToken(),
                Timestamp.from(now));
        if (updated != 1) {
            throw new ProviderWorkConsistencyException(
                    "Provider work lease was lost while marking unresolved");
        }
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markAmbiguous(ProviderWorkClaim claim, String reasonCode) {
        Instant now = clock.instant();
        jdbc.update("""
                INSERT INTO provider.operational_events
                    (event_id, tenant_id, work_id, event_type, safe_reason_code, occurred_at)
                VALUES (?, ?, ?, 'SUBMISSION_OUTCOME_AMBIGUOUS', ?, ?)
                """, UUID.randomUUID(), claim.tenantId(), claim.workId(), reasonCode,
                Timestamp.from(now));
        int updated = jdbc.update("""
                UPDATE provider.work
                   SET status = 'WAITING_STATUS', due_at = ?, lease_owner = NULL, lease_token = NULL,
                       lease_expires_at = NULL, last_error_code = ?, updated_at = ?
                 WHERE id = ? AND status = 'CLAIMED' AND lease_token = ?
                   AND lease_expires_at > ?
                """, Timestamp.from(now.plus(ProviderSchedule.statusDelay(claim.workId(), 1))),
                reasonCode, Timestamp.from(now), claim.workId(), claim.leaseToken(),
                Timestamp.from(now));
        if (updated != 1) {
            throw new ProviderWorkConsistencyException(
                    "Provider work lease was lost while recording ambiguity");
        }
        createStatusQueryWork(claim, now);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(ProviderWorkClaim claim, ProviderCallResult result) {
        Instant now = clock.instant();
        Integer fenced = jdbc.queryForObject("""
                SELECT count(*) FROM provider.work
                 WHERE id = ? AND status = 'CLAIMED' AND lease_token = ?
                   AND lease_expires_at > ?
                """, Integer.class, claim.workId(), claim.leaseToken(), Timestamp.from(now));
        if (fenced == null || fenced != 1) {
            throw new ProviderWorkConsistencyException("Provider work lease is stale");
        }
        UUID interactionId = UUID.randomUUID();
        UUID evidenceId;
        jdbc.update("""
                INSERT INTO provider.interactions
                    (interaction_id, tenant_id, work_id, attempt_id, payment_id,
                     provider_id, work_type, request_id, request_body_hash,
                     response_body_hash, http_status, communication_outcome,
                     latency_millis, safe_error_code, started_at, completed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, interactionId, claim.tenantId(), claim.workId(), claim.attemptId(),
                claim.paymentId(), claim.providerId(), claim.workType().name(), result.requestId(),
                result.requestBodyHash(), result.responseBodyHash(), result.httpStatus(),
                result.communicationOutcome(), result.latencyMillis(), result.safeErrorCode(),
                Timestamp.from(result.startedAt()), Timestamp.from(result.completedAt()));
        Optional<ExistingResult> existing = existingResult(
                claim.tenantId(), claim.providerId(), result.providerResultId());
        if (existing.isPresent()) {
            ExistingResult value = existing.orElseThrow();
            if (!value.matches(claim, result)) {
                throw new ProviderWorkConsistencyException(
                        "Provider result identity was reused with conflicting content");
            }
            evidenceId = value.evidenceId();
        } else {
            evidenceId = UUID.randomUUID();
            int inserted = jdbc.update("""
                    INSERT INTO provider.results
                        (evidence_id, tenant_id, interaction_id, work_id, attempt_id,
                         payment_id, provider_id, provider_idempotency_key,
                         provider_result_id, provider_reference,
                         result_category, retry_disposition, provider_transaction_found,
                         no_acceptance_proven, evidence_origin, observed_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (tenant_id, provider_id, provider_result_id) DO NOTHING
                    """, evidenceId, claim.tenantId(), interactionId, claim.workId(),
                    claim.attemptId(), claim.paymentId(), claim.providerId(),
                    claim.providerIdempotencyKey(), result.providerResultId(),
                    result.providerReference(), result.category().name(),
                    result.disposition().name(), result.providerTransactionFound(),
                    result.noAcceptanceProven(), origin(claim),
                    Timestamp.from(result.completedAt()));
            if (inserted == 0) {
                ExistingResult raced = existingResult(
                        claim.tenantId(), claim.providerId(), result.providerResultId())
                        .orElseThrow(() -> new ProviderWorkConsistencyException(
                                "Provider result conflict did not expose durable evidence"));
                if (!raced.matches(claim, result)) {
                    throw new ProviderWorkConsistencyException(
                            "Provider result identity raced with conflicting content");
                }
                evidenceId = raced.evidenceId();
            }
        }

        ExistingResult durable = existingResult(
                claim.tenantId(), claim.providerId(), result.providerResultId())
                .orElseThrow(() -> new ProviderWorkConsistencyException(
                        "Provider result evidence was not durable before outbox append"));
        ResultMessage message = durable.message();
        outbox.appendOrGet(resultDraft(claim, result, message));
        String status = nextStatus(claim, result);
        Instant dueAt = nextDueAt(claim, result, now);
        if (claim.workType() == ProviderWorkType.SUBMISSION
                && result.disposition() == RetryDisposition.STATUS_RECOVERY_REQUIRED) {
            createStatusQueryWork(claim, now);
        }
        if (claim.workType() == ProviderWorkType.STATUS_QUERY
                && result.disposition() != RetryDisposition.STATUS_RECOVERY_REQUIRED) {
            settleSubmissionRecoveryOwner(claim, result, now);
        }
        int updated = jdbc.update("""
                UPDATE provider.work
                   SET status = ?, due_at = ?, lease_owner = NULL, lease_token = NULL,
                       lease_expires_at = NULL, last_request_id = ?, last_error_code = ?,
                       updated_at = ?
                 WHERE id = ? AND status = 'CLAIMED' AND lease_token = ?
                   AND lease_expires_at > ?
                """, status, Timestamp.from(dueAt), result.requestId(), result.safeErrorCode(),
                Timestamp.from(now), claim.workId(), claim.leaseToken(), Timestamp.from(now));
        if (updated != 1) {
            throw new ProviderWorkConsistencyException("Provider work lease was lost while recording");
        }
    }

    private String nextStatus(ProviderWorkClaim claim, ProviderCallResult result) {
        if (claim.workType() == ProviderWorkType.STATUS_QUERY) {
            return result.disposition() == RetryDisposition.STATUS_RECOVERY_REQUIRED
                    ? "WAITING_STATUS" : "COMPLETED";
        }
        if (result.disposition() == RetryDisposition.SAFE_TO_RESUBMIT
                && claim.attemptSequence() >= 3) {
            recordOperationalEvent(claim, "SAFE_RETRY_EXHAUSTED", "MAXIMUM_ATTEMPTS_REACHED");
            return "UNRESOLVED";
        }
        return switch (result.disposition()) {
            case NOT_RETRYABLE -> "COMPLETED";
            case SAFE_TO_RESUBMIT -> "WAITING_RETRY_REQUEST";
            case STATUS_RECOVERY_REQUIRED -> "WAITING_STATUS";
        };
    }

    private Instant nextDueAt(ProviderWorkClaim claim, ProviderCallResult result, Instant now) {
        if (claim.workType() == ProviderWorkType.STATUS_QUERY
                && result.disposition() == RetryDisposition.STATUS_RECOVERY_REQUIRED) {
            return now.plus(ProviderSchedule.statusDelay(claim.workId(),
                    Math.min(12, claim.exhausted() ? 12 : statusExecutionCount(claim.workId()) + 1)));
        }
        if (claim.workType() == ProviderWorkType.SUBMISSION
                && result.disposition() == RetryDisposition.SAFE_TO_RESUBMIT
                && claim.attemptSequence() < 3) {
            return now.plus(ProviderSchedule.retryDelay(claim.workId(), claim.attemptSequence() + 1));
        }
        return now;
    }

    private int statusExecutionCount(UUID workId) {
        Integer count = jdbc.queryForObject(
                "SELECT execution_count FROM provider.work WHERE id = ?",
                Integer.class, workId);
        return count == null ? 1 : count;
    }

    private void createStatusQueryWork(ProviderWorkClaim submission, Instant now) {
        UUID statusWorkId = UUID.randomUUID();
        Instant dueAt = now.plus(ProviderSchedule.statusDelay(statusWorkId, 1));
        jdbc.update("""
                INSERT INTO provider.work
                    (id, tenant_id, attempt_id, payment_id, attempt_sequence, work_type,
                     status, provider_id, provider_idempotency_key, request_intent_hash,
                     command_payload, due_at, correlation_id, causation_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'STATUS_QUERY', 'PENDING', ?, ?, ?, '{}', ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, attempt_id, work_type) DO NOTHING
                """, statusWorkId, submission.tenantId(), submission.attemptId(),
                submission.paymentId(), submission.attemptSequence(), submission.providerId(),
                submission.providerIdempotencyKey(), submission.requestIntentHash(),
                Timestamp.from(dueAt), submission.correlationId(), submission.causationId(),
                Timestamp.from(now), Timestamp.from(now));
    }

    private void recordOperationalEvent(
            ProviderWorkClaim claim, String eventType, String reasonCode) {
        jdbc.update("""
                INSERT INTO provider.operational_events
                    (event_id, tenant_id, work_id, event_type, safe_reason_code, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), claim.tenantId(), claim.workId(), eventType,
                reasonCode, Timestamp.from(clock.instant()));
    }

    @Override
    public Optional<ProviderEvidence> find(UUID tenantId, UUID evidenceId) {
        return jdbc.query("""
                SELECT evidence_id, tenant_id, payment_id, attempt_id, provider_id,
                       provider_idempotency_key, provider_result_id, provider_reference, result_category,
                       retry_disposition, provider_transaction_found,
                       no_acceptance_proven, evidence_origin, observed_at
                  FROM provider.results WHERE tenant_id = ? AND evidence_id = ?
                """, rs -> rs.next() ? Optional.of(new ProviderEvidence(
                        rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getObject(3, UUID.class), rs.getObject(4, UUID.class),
                        rs.getString(5), rs.getString(6), rs.getObject(7, UUID.class),
                        rs.getString(8), ProviderResultCategory.valueOf(rs.getString(9)),
                        RetryDisposition.valueOf(rs.getString(10)), rs.getBoolean(11),
                        rs.getBoolean(12), rs.getString(13),
                        rs.getTimestamp(14).toInstant())) : Optional.empty(),
                tenantId, evidenceId);
    }

    private OutboxMessageDraft resultDraft(
            ProviderWorkClaim claim, ProviderCallResult result, ResultMessage message) {
        String payload = "{" +
                "\"attemptId\":\"" + claim.attemptId() + "\"," +
                "\"evidenceOrigin\":\"" + message.origin() + "\"," +
                "\"observedAt\":\"" + message.observedAt() + "\"," +
                "\"paymentId\":\"" + claim.paymentId() + "\"," +
                "\"providerEvidenceId\":\"" + message.evidenceId() + "\"," +
                "\"providerId\":\"SIMULATOR\"," +
                "\"providerIdempotencyKey\":\"" + message.providerIdempotencyKey() + "\"," +
                (message.providerReference() == null ? "" :
                        "\"providerReference\":\"" + message.providerReference() + "\",") +
                "\"providerResultId\":\"" + result.providerResultId() + "\"," +
                "\"providerResultCategory\":\"" + message.category() + "\"," +
                "\"retryDisposition\":\"" + message.disposition() + "\"}";
        return new OutboxMessageDraft(
                ProducerName.PROVIDER,
                "provider-result:" + claim.tenantId() + ":SIMULATOR:" + result.providerResultId(),
                "ProviderResultObserved", 1, claim.paymentId(), claim.tenantId(),
                "ledgerops.provider.results.v1", claim.paymentId().toString(), payload,
                claim.correlationId(), claim.causationId(), result.completedAt());
    }

    private void settleSubmissionRecoveryOwner(
            ProviderWorkClaim statusClaim, ProviderCallResult result, Instant now) {
        UUID settlementToken = UUID.randomUUID();
        UUID submissionId = jdbc.query("""
                UPDATE provider.work
                   SET status = 'CLAIMED', lease_owner = 'status-query-settlement',
                       lease_token = ?, lease_expires_at = ?, updated_at = ?
                 WHERE tenant_id = ? AND attempt_id = ? AND work_type = 'SUBMISSION'
                   AND status = 'WAITING_STATUS' AND lease_token IS NULL
                RETURNING id
                """, rs -> rs.next() ? rs.getObject(1, UUID.class) : null,
                settlementToken, Timestamp.from(now.plus(LEASE)), Timestamp.from(now),
                statusClaim.tenantId(), statusClaim.attemptId());
        if (submissionId == null) {
            return;
        }
        String status = result.disposition() == RetryDisposition.SAFE_TO_RESUBMIT
                && statusClaim.attemptSequence() < 3 ? "WAITING_RETRY_REQUEST" : "COMPLETED";
        if (result.disposition() == RetryDisposition.SAFE_TO_RESUBMIT
                && statusClaim.attemptSequence() >= 3) {
            status = "UNRESOLVED";
            recordOperationalEvent(statusClaim, "SAFE_RETRY_EXHAUSTED", "MAXIMUM_ATTEMPTS_REACHED");
        }
        Instant dueAt = "WAITING_RETRY_REQUEST".equals(status)
                ? now.plus(ProviderSchedule.retryDelay(submissionId, statusClaim.attemptSequence() + 1))
                : now;
        int settled = jdbc.update("""
                UPDATE provider.work
                   SET status = ?, due_at = ?, lease_owner = NULL, lease_token = NULL,
                       lease_expires_at = NULL, updated_at = ?
                 WHERE id = ? AND status = 'CLAIMED' AND lease_token = ?
                """, status, Timestamp.from(dueAt), Timestamp.from(now), submissionId,
                settlementToken);
        if (settled != 1) {
            throw new ProviderWorkConsistencyException(
                    "Originating submission recovery ownership could not be settled");
        }
    }

    private void requireCurrentLease(UUID workId, UUID leaseToken, Instant now) {
        Integer current = jdbc.queryForObject("""
                SELECT count(*) FROM provider.work
                 WHERE id = ? AND status = 'CLAIMED' AND lease_token = ?
                   AND lease_expires_at > ?
                """, Integer.class, workId, leaseToken, Timestamp.from(now));
        if (current == null || current != 1) {
            throw new ProviderWorkConsistencyException("Provider work lease is stale");
        }
    }

    private Optional<ExistingResult> existingResult(
            UUID tenantId, String providerId, UUID providerResultId) {
        return jdbc.query("""
                SELECT evidence_id, payment_id, attempt_id, provider_idempotency_key,
                       provider_reference,
                       result_category, retry_disposition, provider_transaction_found,
                       no_acceptance_proven, evidence_origin, observed_at
                  FROM provider.results
                 WHERE tenant_id = ? AND provider_id = ? AND provider_result_id = ?
                """, rs -> rs.next() ? Optional.of(new ExistingResult(
                        rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getObject(3, UUID.class), rs.getString(4), rs.getString(5),
                        rs.getString(6), rs.getString(7), rs.getBoolean(8),
                        rs.getBoolean(9), rs.getString(10), rs.getTimestamp(11).toInstant()))
                        : Optional.empty(), tenantId, providerId, providerResultId);
    }

    private record ExistingResult(
            UUID evidenceId,
            UUID paymentId,
            UUID attemptId,
            String providerIdempotencyKey,
            String providerReference,
            String category,
            String disposition,
            boolean providerTransactionFound,
            boolean noAcceptanceProven,
            String origin,
            Instant observedAt
    ) {
        boolean matches(ProviderWorkClaim claim, ProviderCallResult result) {
            return paymentId.equals(claim.paymentId())
                    && attemptId.equals(claim.attemptId())
                    && java.util.Objects.equals(providerReference, result.providerReference())
                    && category.equals(result.category().name())
                    && disposition.equals(result.disposition().name())
                    && providerTransactionFound == result.providerTransactionFound()
                    && noAcceptanceProven == result.noAcceptanceProven();
        }

        ResultMessage message() {
            return new ResultMessage(evidenceId, providerIdempotencyKey, providerReference,
                    category, disposition, origin, observedAt);
        }
    }

    private String origin(ProviderWorkClaim claim) {
        return claim.workType() == ProviderWorkType.SUBMISSION
                ? "SUBMISSION_RESPONSE" : "STATUS_QUERY";
    }

    private record ResultMessage(
            UUID evidenceId,
            String providerIdempotencyKey,
            String providerReference,
            String category,
            String disposition,
            String origin,
            Instant observedAt
    ) {
    }

    private record RetryRequest(UUID retryRequestId, Instant requestedAt) {
    }
}
