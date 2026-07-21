package com.ledgerops.simulator;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Component
class SimulatorWebhookDeliveryStore {
    private static final Duration LEASE = Duration.ofSeconds(30);

    private final JdbcTemplate jdbc;
    private final Clock clock;

    SimulatorWebhookDeliveryStore(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    Optional<SimulatorWebhookClaim> claimNext(String leaseOwner) {
        Instant now = clock.instant();
        UUID token = UUID.randomUUID();
        Instant expiresAt = now.plus(LEASE);
        return jdbc.query("""
                WITH candidate AS (
                    SELECT delivery_id
                      FROM simulator.webhook_deliveries
                     WHERE ((status IN ('PENDING', 'RETRYABLE') AND next_attempt_at <= ?)
                            OR (status = 'CLAIMED' AND lease_expires_at <= ?))
                       AND attempt_count < 5
                     ORDER BY next_attempt_at, created_at
                     FOR UPDATE SKIP LOCKED
                     LIMIT 1
                ), claimed AS (
                    UPDATE simulator.webhook_deliveries d
                       SET status = 'CLAIMED', lease_owner = ?, lease_token = ?,
                           lease_expires_at = ?, attempt_count = attempt_count + 1,
                           updated_at = ?
                      FROM candidate
                     WHERE d.delivery_id = candidate.delivery_id
                    RETURNING d.*
                )
                SELECT * FROM claimed
                """, rs -> rs.next() ? Optional.of(new SimulatorWebhookClaim(
                        rs.getObject("delivery_id", UUID.class),
                        rs.getObject("provider_event_id", UUID.class),
                        rs.getString("payload"), rs.getString("signature_mode"),
                        rs.getInt("repeat_remaining"), rs.getInt("attempt_count"),
                        rs.getString("traceparent"), rs.getString("tracestate"),
                        rs.getObject("lease_token", UUID.class),
                        rs.getTimestamp("lease_expires_at").toInstant())) : Optional.empty(),
                Timestamp.from(now), Timestamp.from(now), leaseOwner, token,
                Timestamp.from(expiresAt), Timestamp.from(now));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void record(SimulatorWebhookClaim claim, int httpStatus, String errorCode) {
        Instant now = clock.instant();
        boolean accepted = httpStatus == 202 || httpStatus == 409;
        boolean repeat = accepted && claim.repeatRemaining() > 0;
        boolean delivered = accepted && !repeat;
        boolean retryable = (repeat || (!accepted && httpStatus >= 500))
                && claim.attemptCount() < 5;
        String status = delivered ? "DELIVERED" : retryable ? "RETRYABLE" : "DEAD";
        Instant nextAttempt = repeat ? now : retryable
                ? now.plusSeconds(Math.min(60, 1L << Math.min(5, claim.attemptCount() - 1)))
                : now;
        int updated = jdbc.update("""
                UPDATE simulator.webhook_deliveries
                   SET status = ?, next_attempt_at = ?, lease_owner = NULL,
                       lease_token = NULL, lease_expires_at = NULL,
                       repeat_remaining = CASE WHEN ? THEN repeat_remaining - 1
                                               ELSE repeat_remaining END,
                       last_http_status = ?, last_error_code = ?, updated_at = ?,
                       delivered_at = CASE WHEN ? THEN ? ELSE delivered_at END
                 WHERE delivery_id = ? AND status = 'CLAIMED' AND lease_token = ?
                   AND lease_expires_at > ?
                """, status, Timestamp.from(nextAttempt), repeat, httpStatus, errorCode,
                Timestamp.from(now), delivered, Timestamp.from(now), claim.deliveryId(),
                claim.leaseToken(), Timestamp.from(now));
        if (updated != 1) {
            throw new IllegalStateException("Simulator webhook delivery lease is stale");
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void recordTransportFailure(SimulatorWebhookClaim claim, String errorCode) {
        record(claim, 599, errorCode);
    }
}
