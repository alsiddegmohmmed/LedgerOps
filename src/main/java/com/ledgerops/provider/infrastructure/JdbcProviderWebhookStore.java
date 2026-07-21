package com.ledgerops.provider.infrastructure;

import com.ledgerops.messaging.api.MessageOutbox;
import com.ledgerops.provider.api.ProviderResultCategory;
import com.ledgerops.provider.api.RetryDisposition;
import com.ledgerops.provider.application.ProviderWebhookAuthenticationResult;
import com.ledgerops.provider.application.ProviderWebhookClaim;
import com.ledgerops.provider.application.ProviderWebhookExecutionStore;
import com.ledgerops.provider.application.ProviderWebhookPayload;
import com.ledgerops.provider.application.ProviderWebhookProcessingOutcome;
import com.ledgerops.provider.application.ProviderWebhookReceptionOutcome;
import com.ledgerops.provider.application.ProviderWebhookRequest;
import com.ledgerops.provider.application.ProviderWebhookStore;
import com.ledgerops.provider.application.ProviderWorkConsistencyException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "ledgerops.provider.webhook.enabled", havingValue = "true")
class JdbcProviderWebhookStore implements ProviderWebhookStore, ProviderWebhookExecutionStore {
    private static final Duration LEASE = Duration.ofSeconds(30);

    private final JdbcTemplate jdbc;
    private final MessageOutbox outbox;
    private final Clock clock;

    JdbcProviderWebhookStore(JdbcTemplate jdbc, MessageOutbox outbox, Clock clock) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.clock = clock;
    }

    @Override
    public void recordPlatformRejection(
            ProviderWebhookRequest request, String bodyHash, String reasonCode) {
        jdbc.update("""
                INSERT INTO provider.platform_security_rejections
                    (rejection_id, reason_code, key_id, raw_body_hash, body_size, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), reasonCode, bounded(request.keyId(), 128), bodyHash,
                request.rawBody() == null ? 0 : request.rawBody().length,
                Timestamp.from(request.receivedAt()));
    }

    @Override
    public void recordInvalidAuthenticated(
            ProviderWebhookRequest request,
            ProviderWebhookAuthenticationResult authentication,
            String bodyHash,
            String reasonCode
    ) {
        jdbc.update("""
                INSERT INTO provider.unattributed_webhook_evidence
                    (evidence_id, provider_id, provider_client_id, provider_event_id,
                     payload_hash, canonical_payload, outcome, safe_reason_code, occurred_at)
                VALUES (?, ?, ?, ?, ?, NULL, 'INVALID_JSON', ?, ?)
                """, UUID.randomUUID(), authentication.providerId(),
                authentication.providerClientId(), UUID.fromString(request.eventId()), bodyHash,
                reasonCode, Timestamp.from(request.receivedAt()));
    }

    @Override
    public ProviderWebhookReceptionOutcome receiveAuthenticated(
            ProviderWebhookRequest request,
            ProviderWebhookAuthenticationResult authentication,
            ProviderWebhookPayload payload,
            String bodyHash
    ) {
        List<Mapping> mappings = jdbc.query("""
                SELECT tenant_id, payment_id, attempt_id, attempt_sequence,
                       provider_idempotency_key
                  FROM provider.work
                 WHERE provider_id = ? AND provider_idempotency_key = ?
                 ORDER BY attempt_sequence DESC, created_at DESC
                """, (rs, rowNumber) -> new Mapping(
                        rs.getObject("tenant_id", UUID.class),
                        rs.getObject("payment_id", UUID.class),
                        rs.getObject("attempt_id", UUID.class),
                        rs.getInt("attempt_sequence"),
                        rs.getString("provider_idempotency_key")),
                authentication.providerId(), payload.providerIdempotencyKey());
        Mapping mapping = unambiguousLatest(mappings);
        if (mapping == null) {
            jdbc.update("""
                    INSERT INTO provider.unattributed_webhook_evidence
                        (evidence_id, provider_id, provider_client_id, provider_event_id,
                         payload_hash, canonical_payload, outcome, safe_reason_code, occurred_at)
                    VALUES (?, ?, ?, ?, ?, ?, 'UNMAPPED', 'PROVIDER_MAPPING_NOT_FOUND', ?)
                    """, UUID.randomUUID(), authentication.providerId(),
                    authentication.providerClientId(), payload.providerEventId(), bodyHash,
                    payload.canonicalJson(), Timestamp.from(request.receivedAt()));
            jdbc.update("""
                    INSERT INTO provider.webhook_operational_events
                        (operational_event_id, provider_id, provider_event_id, event_type,
                         safe_reason_code, occurred_at)
                    VALUES (?, ?, ?, 'UNMAPPED', 'PROVIDER_MAPPING_NOT_FOUND', ?)
                    """, UUID.randomUUID(), authentication.providerId(),
                    payload.providerEventId(), Timestamp.from(request.receivedAt()));
            return ProviderWebhookReceptionOutcome.UNMAPPED;
        }

        UUID eventId = UUID.randomUUID();
        int inserted = jdbc.update("""
                INSERT INTO provider.webhook_events
                    (event_id, tenant_id, provider_id, provider_client_id,
                     provider_event_id, payment_id, attempt_id, attempt_sequence,
                     provider_idempotency_key, provider_result_id, provider_reference,
                     result_category, provider_occurred_at, payload_hash, canonical_payload,
                     status, correlation_id, received_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?, ?, ?)
                ON CONFLICT (tenant_id, provider_id, provider_event_id) DO NOTHING
                """, eventId, mapping.tenantId(), authentication.providerId(),
                authentication.providerClientId(), payload.providerEventId(), mapping.paymentId(),
                mapping.attemptId(), mapping.attemptSequence(), mapping.providerKey(),
                payload.providerResultId(), payload.providerReference(), payload.category().name(),
                Timestamp.from(payload.providerOccurredAt()), bodyHash, payload.canonicalJson(),
                request.correlationId(), Timestamp.from(request.receivedAt()),
                Timestamp.from(request.receivedAt()));
        if (inserted == 1) {
            insertReceipt(request, mapping.tenantId(), eventId, payload.providerEventId(),
                    bodyHash, "NEW");
            return ProviderWebhookReceptionOutcome.ACCEPTED;
        }

        ExistingEvent existing = jdbc.query("""
                SELECT event_id, payload_hash
                  FROM provider.webhook_events
                 WHERE tenant_id = ? AND provider_id = ? AND provider_event_id = ?
                """, rs -> rs.next() ? new ExistingEvent(
                        rs.getObject(1, UUID.class), rs.getString(2)) : null,
                mapping.tenantId(), authentication.providerId(), payload.providerEventId());
        if (existing == null) {
            throw new IllegalStateException("Webhook event conflict did not expose its row");
        }
        if (existing.payloadHash().equals(bodyHash)) {
            insertReceipt(request, mapping.tenantId(), existing.eventId(),
                    payload.providerEventId(), bodyHash, "DUPLICATE");
            return ProviderWebhookReceptionOutcome.DUPLICATE;
        }
        insertReceipt(request, mapping.tenantId(), existing.eventId(),
                payload.providerEventId(), bodyHash, "CONFLICT");
        jdbc.update("""
                INSERT INTO provider.webhook_operational_events
                    (operational_event_id, tenant_id, event_id, provider_id,
                     provider_event_id, event_type, safe_reason_code, occurred_at)
                VALUES (?, ?, ?, 'SIMULATOR', ?, 'PAYLOAD_CONFLICT',
                        'SAME_EVENT_ID_DIFFERENT_PAYLOAD', ?)
                """, UUID.randomUUID(), mapping.tenantId(), existing.eventId(),
                payload.providerEventId(), Timestamp.from(request.receivedAt()));
        return ProviderWebhookReceptionOutcome.CONFLICT;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<ProviderWebhookClaim> claimNextWebhook(String leaseOwner) {
        Instant now = clock.instant();
        UUID leaseToken = UUID.randomUUID();
        Instant leaseExpiresAt = now.plus(LEASE);
        return jdbc.query("""
                WITH candidate AS (
                    SELECT event_id
                      FROM provider.webhook_events
                     WHERE status = 'PENDING'
                        OR (status = 'CLAIMED' AND lease_expires_at <= ?)
                     ORDER BY received_at, event_id
                     FOR UPDATE SKIP LOCKED
                     LIMIT 1
                ), claimed AS (
                    UPDATE provider.webhook_events e
                       SET status = 'CLAIMED', lease_owner = ?, lease_token = ?,
                           lease_expires_at = ?, updated_at = ?
                      FROM candidate
                     WHERE e.event_id = candidate.event_id
                    RETURNING e.*
                )
                SELECT * FROM claimed
                """, rs -> rs.next() ? Optional.of(new ProviderWebhookClaim(
                        rs.getObject("event_id", UUID.class),
                        rs.getObject("tenant_id", UUID.class),
                        rs.getObject("payment_id", UUID.class),
                        rs.getObject("attempt_id", UUID.class),
                        rs.getInt("attempt_sequence"),
                        rs.getString("provider_id"),
                        rs.getString("provider_idempotency_key"),
                        rs.getObject("provider_event_id", UUID.class),
                        rs.getObject("provider_result_id", UUID.class),
                        rs.getString("provider_reference"),
                        ProviderResultCategory.valueOf(rs.getString("result_category")),
                        rs.getTimestamp("provider_occurred_at").toInstant(),
                        rs.getString("payload_hash"),
                        rs.getObject("correlation_id", UUID.class),
                        rs.getTimestamp("received_at").toInstant(),
                        rs.getObject("lease_token", UUID.class),
                        rs.getTimestamp("lease_expires_at").toInstant()
                )) : Optional.empty(), Timestamp.from(now), leaseOwner, leaseToken,
                Timestamp.from(leaseExpiresAt), Timestamp.from(now));
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ProviderWebhookProcessingOutcome processWebhook(ProviderWebhookClaim claim) {
        Instant now = clock.instant();
        requireCurrentLease(claim, now);

        UUID interactionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO provider.interactions
                    (interaction_id, tenant_id, work_id, webhook_event_id, attempt_id,
                     payment_id, provider_id, work_type, request_id, request_body_hash,
                     response_body_hash, http_status, communication_outcome, latency_millis,
                     safe_error_code, started_at, completed_at)
                VALUES (?, ?, NULL, ?, ?, ?, ?, 'WEBHOOK', ?, ?, NULL, 202,
                        'RESPONSE', 0, NULL, ?, ?)
                """, interactionId, claim.tenantId(), claim.eventId(), claim.attemptId(),
                claim.paymentId(), claim.providerId(), claim.providerEventId(),
                claim.payloadHash(), Timestamp.from(claim.receivedAt()), Timestamp.from(now));

        RetryDisposition disposition = disposition(claim.category());
        Optional<WebhookResult> existing = existingResult(
                claim.tenantId(), claim.providerId(), claim.providerResultId());
        UUID evidenceId;
        if (existing.isPresent()) {
            WebhookResult result = existing.orElseThrow();
            if (!result.matches(claim, disposition)) {
                recordResultConflict(claim, now);
                completeClaim(claim, "UNRESOLVED", now);
                return ProviderWebhookProcessingOutcome.RESULT_CONFLICT;
            }
            evidenceId = result.evidenceId();
        } else {
            evidenceId = UUID.randomUUID();
            int inserted = jdbc.update("""
                    INSERT INTO provider.results
                        (evidence_id, tenant_id, interaction_id, work_id, webhook_event_id,
                         attempt_id, payment_id, provider_id, provider_idempotency_key,
                         provider_result_id, provider_reference, result_category,
                         retry_disposition, provider_transaction_found,
                         no_acceptance_proven, evidence_origin, observed_at)
                    VALUES (?, ?, ?, NULL, ?, ?, ?, ?, ?, ?, ?, ?, ?, TRUE, FALSE,
                            'WEBHOOK', ?)
                    ON CONFLICT (tenant_id, provider_id, provider_result_id) DO NOTHING
                    """, evidenceId, claim.tenantId(), interactionId, claim.eventId(),
                    claim.attemptId(), claim.paymentId(), claim.providerId(),
                    claim.providerIdempotencyKey(), claim.providerResultId(),
                    claim.providerReference(), claim.category().name(), disposition.name(),
                    Timestamp.from(claim.providerOccurredAt()));
            if (inserted == 0) {
                WebhookResult raced = existingResult(
                        claim.tenantId(), claim.providerId(), claim.providerResultId())
                        .orElseThrow(() -> new ProviderWorkConsistencyException(
                                "Provider webhook result conflict exposed no durable result"));
                if (!raced.matches(claim, disposition)) {
                    recordResultConflict(claim, now);
                    completeClaim(claim, "UNRESOLVED", now);
                    return ProviderWebhookProcessingOutcome.RESULT_CONFLICT;
                }
                evidenceId = raced.evidenceId();
            }
        }

        WebhookResult durable = existingResult(
                claim.tenantId(), claim.providerId(), claim.providerResultId())
                .orElseThrow(() -> new ProviderWorkConsistencyException(
                        "Provider webhook result was not durable before outbox append"));
        outbox.appendOrGet(ProviderResultOutboxFactory.draft(
                claim.tenantId(), claim.paymentId(), claim.attemptId(), evidenceId,
                claim.providerResultId(), claim.providerIdempotencyKey(),
                durable.providerReference(), durable.category(), durable.disposition(),
                durable.origin(), durable.observedAt(), claim.correlationId(),
                claim.providerEventId(), now));
        if (disposition == RetryDisposition.STATUS_RECOVERY_REQUIRED) {
            createStatusRecovery(claim, now);
        }
        completeClaim(claim, "COMPLETED", now);
        return ProviderWebhookProcessingOutcome.COMPLETED;
    }

    private RetryDisposition disposition(ProviderResultCategory category) {
        return switch (category) {
            case SUCCESS, DECLINED, PERMANENT_FAILURE -> RetryDisposition.NOT_RETRYABLE;
            case ACCEPTED, PENDING, TEMPORARY_FAILURE, UNKNOWN ->
                    RetryDisposition.STATUS_RECOVERY_REQUIRED;
        };
    }

    private Optional<WebhookResult> existingResult(
            UUID tenantId, String providerId, UUID providerResultId) {
        return jdbc.query("""
                SELECT evidence_id, payment_id, attempt_id, provider_idempotency_key,
                       provider_reference, result_category, retry_disposition,
                       provider_transaction_found, no_acceptance_proven,
                       evidence_origin, observed_at
                  FROM provider.results
                 WHERE tenant_id = ? AND provider_id = ? AND provider_result_id = ?
                """, rs -> rs.next() ? Optional.of(new WebhookResult(
                        rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getObject(3, UUID.class), rs.getString(4), rs.getString(5),
                        rs.getString(6), rs.getString(7), rs.getBoolean(8),
                        rs.getBoolean(9), rs.getString(10), rs.getTimestamp(11).toInstant()))
                        : Optional.empty(), tenantId, providerId, providerResultId);
    }

    private void recordResultConflict(ProviderWebhookClaim claim, Instant now) {
        jdbc.update("""
                INSERT INTO provider.webhook_operational_events
                    (operational_event_id, tenant_id, event_id, provider_id,
                     provider_event_id, event_type, safe_reason_code, occurred_at)
                VALUES (?, ?, ?, ?, ?, 'RESULT_CONFLICT',
                        'PROVIDER_RESULT_ID_DIFFERENT_CONTENT', ?)
                """, UUID.randomUUID(), claim.tenantId(), claim.eventId(), claim.providerId(),
                claim.providerEventId(), Timestamp.from(now));
    }

    private void createStatusRecovery(ProviderWebhookClaim claim, Instant now) {
        UUID workId = UUID.randomUUID();
        Instant dueAt = now.plus(ProviderSchedule.statusDelay(workId, 1));
        jdbc.update("""
                INSERT INTO provider.work
                    (id, tenant_id, attempt_id, payment_id, attempt_sequence, work_type,
                     status, provider_id, provider_idempotency_key, request_intent_hash,
                     command_payload, due_at, correlation_id, causation_id,
                     created_at, updated_at)
                SELECT ?, w.tenant_id, w.attempt_id, w.payment_id, w.attempt_sequence,
                       'STATUS_QUERY', 'PENDING', w.provider_id,
                       w.provider_idempotency_key, w.request_intent_hash, '{}', ?, ?, ?, ?, ?
                  FROM provider.work w
                 WHERE w.tenant_id = ? AND w.attempt_id = ?
                   AND w.work_type = 'SUBMISSION'
                ON CONFLICT (tenant_id, attempt_id, work_type) DO NOTHING
                """, workId, Timestamp.from(dueAt), claim.correlationId(),
                claim.providerEventId(), Timestamp.from(now), Timestamp.from(now),
                claim.tenantId(), claim.attemptId());
    }

    private void requireCurrentLease(ProviderWebhookClaim claim, Instant now) {
        Integer current = jdbc.queryForObject("""
                SELECT count(*) FROM provider.webhook_events
                 WHERE event_id = ? AND status = 'CLAIMED' AND lease_token = ?
                   AND lease_expires_at > ?
                """, Integer.class, claim.eventId(), claim.leaseToken(), Timestamp.from(now));
        if (current == null || current != 1) {
            throw new ProviderWorkConsistencyException("Provider webhook lease is stale");
        }
    }

    private void completeClaim(ProviderWebhookClaim claim, String status, Instant now) {
        int updated = jdbc.update("""
                UPDATE provider.webhook_events
                   SET status = ?, lease_owner = NULL, lease_token = NULL,
                       lease_expires_at = NULL, updated_at = ?
                 WHERE event_id = ? AND status = 'CLAIMED' AND lease_token = ?
                   AND lease_expires_at > ?
                """, status, Timestamp.from(now), claim.eventId(), claim.leaseToken(),
                Timestamp.from(now));
        if (updated != 1) {
            throw new ProviderWorkConsistencyException(
                    "Provider webhook lease was lost during completion");
        }
    }

    private void insertReceipt(
            ProviderWebhookRequest request,
            UUID tenantId,
            UUID eventId,
            UUID providerEventId,
            String bodyHash,
            String outcome
    ) {
        jdbc.update("""
                INSERT INTO provider.webhook_receipts
                    (receipt_id, tenant_id, event_id, provider_id, provider_event_id,
                     payload_hash, outcome, correlation_id, received_at)
                VALUES (?, ?, ?, 'SIMULATOR', ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), tenantId, eventId, providerEventId, bodyHash, outcome,
                request.correlationId(), Timestamp.from(request.receivedAt()));
    }

    private Mapping unambiguousLatest(List<Mapping> mappings) {
        if (mappings.isEmpty()) return null;
        Mapping latest = mappings.getFirst();
        boolean unambiguous = mappings.stream().allMatch(mapping ->
                mapping.tenantId().equals(latest.tenantId())
                        && mapping.paymentId().equals(latest.paymentId())
                        && mapping.providerKey().equals(latest.providerKey()));
        return unambiguous ? latest : null;
    }

    private String bounded(String value, int maximum) {
        if (value == null) return null;
        return value.length() <= maximum ? value : value.substring(0, maximum);
    }

    private record Mapping(
            UUID tenantId,
            UUID paymentId,
            UUID attemptId,
            int attemptSequence,
            String providerKey
    ) {
    }

    private record ExistingEvent(UUID eventId, String payloadHash) {
    }

    private record WebhookResult(
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
        boolean matches(ProviderWebhookClaim claim, RetryDisposition expectedDisposition) {
            return paymentId.equals(claim.paymentId())
                    && attemptId.equals(claim.attemptId())
                    && providerIdempotencyKey.equals(claim.providerIdempotencyKey())
                    && Objects.equals(providerReference, claim.providerReference())
                    && category.equals(claim.category().name())
                    && disposition.equals(expectedDisposition.name())
                    && providerTransactionFound
                    && !noAcceptanceProven;
        }
    }
}
