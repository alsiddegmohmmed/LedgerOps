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
                WITH candidate AS (
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
                        ProviderWorkType.valueOf(rs.getString("work_type")),
                        rs.getString("provider_id"),
                        rs.getString("provider_idempotency_key"),
                        rs.getString("request_intent_hash"),
                        rs.getString("command_payload"),
                        rs.getObject("correlation_id", UUID.class),
                        rs.getObject("causation_id", UUID.class),
                        rs.getObject("lease_token", UUID.class),
                        rs.getTimestamp("lease_expires_at").toInstant(),
                        rs.getBoolean("recovery_only"),
                        rs.getBoolean("exhausted")
                )) : Optional.empty(), Timestamp.from(now), Timestamp.from(now), leaseOwner,
                token, Timestamp.from(expires), Timestamp.from(now));
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
        int updated = jdbc.update("""
                UPDATE provider.work
                   SET status = 'RETRYABLE', due_at = ?, lease_owner = NULL,
                       lease_token = NULL, lease_expires_at = NULL,
                       execution_count = execution_count - 1,
                       last_error_code = ?, updated_at = ?
                 WHERE id = ? AND status = 'CLAIMED' AND lease_token = ?
                   AND lease_expires_at > ?
                """, Timestamp.from(now.plusSeconds(1)), reasonCode, Timestamp.from(now),
                claim.workId(), claim.leaseToken(), Timestamp.from(now));
        if (updated != 1) {
            throw new ProviderWorkConsistencyException("Provider work lease was lost while deferring");
        }
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markUnresolved(ProviderWorkClaim claim, String reasonCode) {
        Instant now = clock.instant();
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
                   SET status = 'WAITING_STATUS', lease_owner = NULL, lease_token = NULL,
                       lease_expires_at = NULL, last_error_code = ?, updated_at = ?
                 WHERE id = ? AND status = 'CLAIMED' AND lease_token = ?
                   AND lease_expires_at > ?
                """, reasonCode, Timestamp.from(now), claim.workId(), claim.leaseToken(),
                Timestamp.from(now));
        if (updated != 1) {
            throw new ProviderWorkConsistencyException(
                    "Provider work lease was lost while recording ambiguity");
        }
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
        String status = switch (result.disposition()) {
            case NOT_RETRYABLE -> "COMPLETED";
            case SAFE_TO_RESUBMIT -> "WAITING_RETRY_REQUEST";
            case STATUS_RECOVERY_REQUIRED -> "WAITING_STATUS";
        };
        if (claim.workType() == ProviderWorkType.STATUS_QUERY) {
            settleSubmissionRecoveryOwner(claim, now);
        }
        int updated = jdbc.update("""
                UPDATE provider.work
                   SET status = ?, due_at = ?, lease_owner = NULL, lease_token = NULL,
                       lease_expires_at = NULL, last_request_id = ?, last_error_code = ?,
                       updated_at = ?
                 WHERE id = ? AND status = 'CLAIMED' AND lease_token = ?
                   AND lease_expires_at > ?
                """, status, Timestamp.from(now), result.requestId(), result.safeErrorCode(),
                Timestamp.from(now), claim.workId(), claim.leaseToken(), Timestamp.from(now));
        if (updated != 1) {
            throw new ProviderWorkConsistencyException("Provider work lease was lost while recording");
        }
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

    private void settleSubmissionRecoveryOwner(ProviderWorkClaim statusClaim, Instant now) {
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
        int settled = jdbc.update("""
                UPDATE provider.work
                   SET status = 'COMPLETED', lease_owner = NULL, lease_token = NULL,
                       lease_expires_at = NULL, updated_at = ?
                 WHERE id = ? AND status = 'CLAIMED' AND lease_token = ?
                """, Timestamp.from(now), submissionId, settlementToken);
        if (settled != 1) {
            throw new ProviderWorkConsistencyException(
                    "Originating submission recovery ownership could not be settled");
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
}
