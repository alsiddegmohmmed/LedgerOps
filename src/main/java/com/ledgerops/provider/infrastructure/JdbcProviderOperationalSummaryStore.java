package com.ledgerops.provider.infrastructure;

import com.ledgerops.provider.api.ProviderHealthEvaluation;
import com.ledgerops.provider.api.ProviderHealthState;
import com.ledgerops.provider.api.ProviderOperationalSummaryQuery;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Provider-owned adapter for the Reporting projection rebuild boundary. */
@Repository
class JdbcProviderOperationalSummaryStore implements ProviderOperationalSummaryQuery {

    private final JdbcTemplate jdbc;

    JdbcProviderOperationalSummaryStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<ProviderHealthEvaluation> latestHealthAtOrBefore(
            String providerId,
            Instant asOf
    ) {
        Objects.requireNonNull(providerId, "Provider ID must not be null");
        Objects.requireNonNull(asOf, "Health as-of time must not be null");
        return jdbc.query("""
                SELECT evaluation_id, provider_id, policy_id, policy_version,
                       health_version, state, completed_calls, successful_communications,
                       timeout_count, system_error_count, p95_latency_millis, circuit_state,
                       window_started_at, window_ended_at, evaluated_at
                  FROM provider.health_evaluations
                 WHERE provider_id = ? AND evaluated_at <= ?
                 ORDER BY evaluated_at DESC, evaluation_id DESC
                 LIMIT 1
                """, this::optionalEvaluation, providerId, Timestamp.from(asOf));
    }

    @Override
    public List<ProviderHealthEvaluation> healthEvaluationsBetween(
            String providerId,
            Instant fromInclusive,
            Instant toExclusive
    ) {
        Objects.requireNonNull(providerId, "Provider ID must not be null");
        Objects.requireNonNull(fromInclusive, "Health period start must not be null");
        Objects.requireNonNull(toExclusive, "Health period end must not be null");
        if (!fromInclusive.isBefore(toExclusive)) {
            throw new IllegalArgumentException("Health period start must be before its exclusive end");
        }
        return jdbc.query("""
                SELECT evaluation_id, provider_id, policy_id, policy_version,
                       health_version, state, completed_calls, successful_communications,
                       timeout_count, system_error_count, p95_latency_millis, circuit_state,
                       window_started_at, window_ended_at, evaluated_at
                  FROM provider.health_evaluations
                 WHERE provider_id = ?
                   AND evaluated_at >= ?
                   AND evaluated_at < ?
                 ORDER BY evaluated_at ASC, evaluation_id ASC
                """, this::mapEvaluation, providerId,
                Timestamp.from(fromInclusive), Timestamp.from(toExclusive));
    }

    private Optional<ProviderHealthEvaluation> optionalEvaluation(ResultSet rs)
            throws SQLException {
        return rs.next() ? Optional.of(mapEvaluation(rs, 0)) : Optional.empty();
    }

    private ProviderHealthEvaluation mapEvaluation(ResultSet rs, int row)
            throws SQLException {
        return new ProviderHealthEvaluation(
                rs.getObject("evaluation_id", java.util.UUID.class),
                rs.getString("provider_id"),
                rs.getObject("policy_id", java.util.UUID.class),
                rs.getLong("policy_version"),
                rs.getLong("health_version"),
                ProviderHealthState.valueOf(rs.getString("state")),
                rs.getInt("completed_calls"),
                rs.getInt("successful_communications"),
                rs.getInt("timeout_count"),
                rs.getInt("system_error_count"),
                rs.getLong("p95_latency_millis"),
                rs.getString("circuit_state"),
                rs.getTimestamp("window_started_at").toInstant(),
                rs.getTimestamp("window_ended_at").toInstant(),
                rs.getTimestamp("evaluated_at").toInstant());
    }
}
