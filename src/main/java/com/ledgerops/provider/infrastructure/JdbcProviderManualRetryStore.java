package com.ledgerops.provider.infrastructure;

import com.ledgerops.provider.api.ProviderManualRetryPort;
import com.ledgerops.provider.api.ProviderRetryAcceleration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
class JdbcProviderManualRetryStore implements ProviderManualRetryPort {

    private final JdbcTemplate jdbc;

    JdbcProviderManualRetryStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public Optional<ProviderRetryAcceleration> accelerateSafeRetry(
            UUID tenantId,
            UUID paymentId,
            Instant now
    ) {
        return jdbc.query("""
                WITH candidate AS (
                    SELECT w.id, w.due_at
                      FROM provider.work w
                     WHERE w.tenant_id = ?
                       AND w.payment_id = ?
                       AND w.work_type = 'SUBMISSION'
                       AND w.status = 'WAITING_RETRY_REQUEST'
                       AND w.attempt_sequence < 3
                       AND EXISTS (
                           SELECT 1
                             FROM provider.results r
                            WHERE r.tenant_id = w.tenant_id
                              AND r.attempt_id = w.attempt_id
                              AND r.retry_disposition = 'SAFE_TO_RESUBMIT'
                              AND NOT EXISTS (
                                  SELECT 1
                                    FROM provider.retry_requests rr
                                   WHERE rr.tenant_id = r.tenant_id
                                     AND rr.provider_evidence_id = r.evidence_id
                              )
                       )
                     ORDER BY w.due_at, w.created_at, w.id
                     FOR UPDATE SKIP LOCKED
                     LIMIT 1
                )
                UPDATE provider.work w
                   SET due_at = LEAST(w.due_at, ?), updated_at = ?
                  FROM candidate
                 WHERE w.id = candidate.id
                RETURNING w.id, candidate.due_at AS previous_due_at, w.due_at
                """, rs -> rs.next() ? Optional.of(new ProviderRetryAcceleration(
                rs.getObject("id", UUID.class),
                rs.getTimestamp("previous_due_at").toInstant(),
                rs.getTimestamp("due_at").toInstant())) : Optional.empty(),
                tenantId, paymentId, Timestamp.from(now), Timestamp.from(now));
    }
}
