package com.ledgerops.messaging.infrastructure;

import com.ledgerops.messaging.application.OutboxClaim;
import com.ledgerops.messaging.application.OutboxDeliveryStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
class JdbcOutboxDeliveryStore implements OutboxDeliveryStore {

    private static final String CLAIM_SQL = """
            WITH candidates AS (
                SELECT id
                  FROM messaging.outbox
                 WHERE (
                       (status IN ('PENDING', 'RETRYABLE') AND next_attempt_at <= ?)
                    OR (status = 'CLAIMED' AND attempt_count < 10 AND lease_expires_at <= ?)
                 )
                 ORDER BY next_attempt_at, created_at
                 FOR UPDATE SKIP LOCKED
                 LIMIT ?
            )
            UPDATE messaging.outbox outbox
               SET status = 'CLAIMED',
                   attempt_count = attempt_count + 1,
                   lease_owner = ?, lease_token = gen_random_uuid(), lease_expires_at = ?
              FROM candidates
             WHERE outbox.id = candidates.id
            RETURNING outbox.id, outbox.message_id, outbox.message_type,
                      outbox.schema_version, outbox.aggregate_id, outbox.tenant_id,
                      outbox.topic, outbox.partition_key, outbox.payload,
                      outbox.correlation_id, outbox.causation_id,
                      outbox.traceparent, outbox.tracestate, outbox.occurred_at,
                      outbox.attempt_count, outbox.lease_token
            """;

    private final JdbcTemplate jdbc;

    JdbcOutboxDeliveryStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public List<OutboxClaim> claimDue(String owner, Instant now, Duration lease, int limit) {
        deadLetterExpiredFinalAttempts(now, limit);
        return jdbc.query(
                CLAIM_SQL,
                this::map,
                Timestamp.from(now), Timestamp.from(now), limit, owner,
                Timestamp.from(now.plus(lease))
        );
    }

    private void deadLetterExpiredFinalAttempts(Instant now, int limit) {
        jdbc.update("""
                WITH candidates AS (
                    SELECT id
                      FROM messaging.outbox
                     WHERE status = 'CLAIMED' AND attempt_count = 10
                       AND lease_expires_at <= ?
                     ORDER BY lease_expires_at, created_at
                     FOR UPDATE SKIP LOCKED
                     LIMIT ?
                ), exhausted AS (
                    UPDATE messaging.outbox
                       SET status = 'DEAD',
                           lease_owner = NULL, lease_token = NULL, lease_expires_at = NULL,
                           last_error_code = 'FINAL_ATTEMPT_LEASE_EXPIRED',
                           last_error_summary = 'Publisher crashed during final publication attempt'
                      FROM candidates
                     WHERE messaging.outbox.id = candidates.id
                    RETURNING messaging.outbox.id
                )
                INSERT INTO messaging.publication_dead_letters
                    (id, outbox_id, reason_code, safe_summary, dead_at)
                SELECT gen_random_uuid(), id, 'FINAL_ATTEMPT_LEASE_EXPIRED',
                       'Publisher crashed during final publication attempt', ?
                  FROM exhausted
                ON CONFLICT (outbox_id) DO NOTHING
                """, Timestamp.from(now), limit, Timestamp.from(now));
    }

    @Override
    @Transactional
    public boolean renew(UUID outboxId, UUID leaseToken, Instant expiresAt) {
        return jdbc.update("""
                UPDATE messaging.outbox
                   SET lease_expires_at = ?
                 WHERE id = ? AND status = 'CLAIMED' AND lease_token = ?
                """, Timestamp.from(expiresAt), outboxId, leaseToken) == 1;
    }

    @Override
    @Transactional
    public boolean markPublished(UUID outboxId, UUID leaseToken, Instant publishedAt) {
        return jdbc.update("""
                UPDATE messaging.outbox
                   SET status = 'PUBLISHED', published_at = ?,
                       lease_owner = NULL, lease_token = NULL, lease_expires_at = NULL,
                       last_error_code = NULL, last_error_summary = NULL
                 WHERE id = ? AND status = 'CLAIMED' AND lease_token = ?
                """, Timestamp.from(publishedAt), outboxId, leaseToken) == 1;
    }

    @Override
    @Transactional
    public boolean markRetryable(
            UUID outboxId,
            UUID leaseToken,
            Instant dueAt,
            String code,
            String summary
    ) {
        return jdbc.update("""
                UPDATE messaging.outbox
                   SET status = 'RETRYABLE', next_attempt_at = ?,
                       lease_owner = NULL, lease_token = NULL, lease_expires_at = NULL,
                       last_error_code = ?, last_error_summary = ?
                 WHERE id = ? AND status = 'CLAIMED' AND lease_token = ?
                """, Timestamp.from(dueAt), code, bounded(summary), outboxId, leaseToken) == 1;
    }

    @Override
    @Transactional
    public boolean markDead(
            UUID outboxId,
            UUID leaseToken,
            Instant deadAt,
            String code,
            String summary
    ) {
        String bounded = bounded(summary);
        int updated = jdbc.update("""
                UPDATE messaging.outbox
                   SET status = 'DEAD',
                       lease_owner = NULL, lease_token = NULL, lease_expires_at = NULL,
                       last_error_code = ?, last_error_summary = ?
                 WHERE id = ? AND status = 'CLAIMED' AND lease_token = ?
                """, code, bounded, outboxId, leaseToken);
        if (updated == 1) {
            jdbc.update("""
                    INSERT INTO messaging.publication_dead_letters
                        (id, outbox_id, reason_code, safe_summary, dead_at)
                    VALUES (?, ?, ?, ?, ?)
                    ON CONFLICT (outbox_id) DO NOTHING
                    """, UUID.randomUUID(), outboxId, code, bounded, Timestamp.from(deadAt));
        }
        return updated == 1;
    }

    private OutboxClaim map(ResultSet rs, int row) throws SQLException {
        return new OutboxClaim(
                rs.getObject("id", UUID.class),
                rs.getObject("message_id", UUID.class),
                rs.getString("message_type"),
                rs.getInt("schema_version"),
                rs.getObject("aggregate_id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getString("topic"),
                rs.getString("partition_key"),
                rs.getString("payload"),
                rs.getObject("correlation_id", UUID.class),
                rs.getObject("causation_id", UUID.class),
                rs.getString("traceparent"),
                rs.getString("tracestate"),
                rs.getTimestamp("occurred_at").toInstant(),
                rs.getInt("attempt_count"),
                rs.getObject("lease_token", UUID.class)
        );
    }

    private String bounded(String value) {
        String safe = value == null || value.isBlank() ? "No safe detail" : value;
        return safe.length() <= 512 ? safe : safe.substring(0, 512);
    }
}
