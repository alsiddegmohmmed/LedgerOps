package com.ledgerops.provider.infrastructure;

import com.ledgerops.messaging.api.MessageOutbox;
import com.ledgerops.messaging.api.OutboxMessageDraft;
import com.ledgerops.messaging.api.ProducerName;
import com.ledgerops.provider.api.ProviderHealthEvaluation;
import com.ledgerops.provider.api.ProviderHealthPolicy;
import com.ledgerops.provider.api.ProviderHealthPort;
import com.ledgerops.provider.api.ProviderHealthState;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Repository
class JdbcProviderHealthStore implements ProviderHealthPort {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final UUID SYSTEM_TENANT_ID = UUID.nameUUIDFromBytes(
            "ledgerops:provider-health:system".getBytes(StandardCharsets.UTF_8));

    private final JdbcTemplate jdbc;
    private final MessageOutbox outbox;
    private final Clock clock;

    JdbcProviderHealthStore(JdbcTemplate jdbc, MessageOutbox outbox, Clock clock) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.clock = clock;
    }

    @Override
    @Transactional
    public ProviderHealthEvaluation evaluate(String providerId, String circuitState, Instant now) {
        Objects.requireNonNull(providerId, "Provider ID must not be null");
        Objects.requireNonNull(circuitState, "Circuit state must not be null");
        Objects.requireNonNull(now, "Evaluation time must not be null");
        String normalizedCircuitState = circuitState.toUpperCase(java.util.Locale.ROOT);
        if (!List.of("CLOSED", "OPEN", "HALF_OPEN").contains(normalizedCircuitState)) {
            throw new IllegalArgumentException("Circuit state must be CLOSED, OPEN, or HALF_OPEN");
        }

        ProviderHealthPolicy policy = activePolicy(providerId);
        Instant windowStart = now.minusSeconds(policy.windowSeconds());
        EvidenceCounts counts = evidence(windowStart, now, providerId);
        ProviderHealthState state = ProviderHealthCalculator.state(
                policy, counts.completedCalls(), counts.successfulCommunications(),
                counts.timeoutCount(), counts.systemErrorCount(), counts.p95LatencyMillis(),
                normalizedCircuitState);
        CurrentHealth previous = currentPointer(providerId);
        long healthVersion = previous == null ? 1
                : previous.state() == state ? previous.healthVersion() : previous.healthVersion() + 1;
        UUID evaluationId = UUID.randomUUID();
        ProviderHealthEvaluation evaluation = new ProviderHealthEvaluation(
                evaluationId, providerId, policy.policyId(), policy.version(), healthVersion, state,
                counts.completedCalls(), counts.successfulCommunications(), counts.timeoutCount(),
                counts.systemErrorCount(), counts.p95LatencyMillis(), normalizedCircuitState,
                windowStart, now, now);

        jdbc.update("""
                INSERT INTO provider.health_evaluations (
                    evaluation_id, provider_id, policy_id, policy_version, health_version,
                    state, completed_calls, successful_communications, timeout_count,
                    system_error_count, p95_latency_millis, circuit_state,
                    window_started_at, window_ended_at, evaluated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, evaluation.evaluationId(), evaluation.providerId(), evaluation.policyId(),
                evaluation.policyVersion(), evaluation.healthVersion(), evaluation.state().name(),
                evaluation.completedCalls(), evaluation.successfulCommunications(),
                evaluation.timeoutCount(), evaluation.systemErrorCount(), evaluation.p95LatencyMillis(),
                evaluation.circuitState(), Timestamp.from(evaluation.windowStartedAt()),
                Timestamp.from(evaluation.windowEndedAt()), Timestamp.from(evaluation.evaluatedAt()));
        jdbc.update("""
                INSERT INTO provider.health_current
                    (provider_id, evaluation_id, health_version, state, updated_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (provider_id) DO UPDATE SET
                    evaluation_id = EXCLUDED.evaluation_id,
                    health_version = EXCLUDED.health_version,
                    state = EXCLUDED.state,
                    updated_at = EXCLUDED.updated_at
                """, providerId, evaluation.evaluationId(), evaluation.healthVersion(),
                evaluation.state().name(), Timestamp.from(now));

        if (previous == null || previous.state() != state) {
            appendStateChanged(previous == null ? null : previous.state(), evaluation);
        }
        return evaluation;
    }

    @Override
    public Optional<ProviderHealthEvaluation> current(String providerId) {
        return jdbc.query("""
                SELECT e.evaluation_id, e.provider_id, e.policy_id, e.policy_version,
                       e.health_version, e.state, e.completed_calls, e.successful_communications,
                       e.timeout_count, e.system_error_count, e.p95_latency_millis, e.circuit_state,
                       e.window_started_at, e.window_ended_at, e.evaluated_at
                  FROM provider.health_current c
                  JOIN provider.health_evaluations e ON e.evaluation_id = c.evaluation_id
                 WHERE c.provider_id = ?
                """, this::mapEvaluation, providerId).stream().findFirst();
    }

    @Override
    public List<ProviderHealthEvaluation> recent(String providerId, int limit) {
        int boundedLimit = Math.max(1, Math.min(100, limit));
        return jdbc.query("""
                SELECT evaluation_id, provider_id, policy_id, policy_version, health_version,
                       state, completed_calls, successful_communications, timeout_count,
                       system_error_count, p95_latency_millis, circuit_state,
                       window_started_at, window_ended_at, evaluated_at
                  FROM provider.health_evaluations
                 WHERE provider_id = ?
                 ORDER BY evaluated_at DESC, evaluation_id DESC
                 LIMIT ?
                """, this::mapEvaluation, providerId, boundedLimit);
    }

    private ProviderHealthPolicy activePolicy(String providerId) {
        return jdbc.query("""
                SELECT policy_id, provider_id, version, window_seconds,
                       evaluation_interval_seconds, minimum_completed_calls,
                       degraded_error_rate, degraded_p95_latency_millis, active, created_at
                  FROM provider.health_policies
                 WHERE provider_id = ? AND active
                 ORDER BY version DESC
                 LIMIT 1
                """, rs -> rs.next() ? new ProviderHealthPolicy(
                rs.getObject("policy_id", UUID.class), rs.getString("provider_id"),
                rs.getLong("version"), rs.getInt("window_seconds"),
                rs.getInt("evaluation_interval_seconds"), rs.getInt("minimum_completed_calls"),
                rs.getBigDecimal("degraded_error_rate").doubleValue(),
                rs.getLong("degraded_p95_latency_millis"), rs.getBoolean("active"),
                rs.getTimestamp("created_at").toInstant()) : null, providerId);
    }

    private EvidenceCounts evidence(Instant windowStart, Instant now, String providerId) {
        return jdbc.query("""
                SELECT COUNT(*)::int,
                       COUNT(*) FILTER (WHERE communication_outcome = 'RESPONSE')::int,
                       COUNT(*) FILTER (WHERE communication_outcome = 'TIMEOUT')::int,
                       COUNT(*) FILTER (WHERE communication_outcome IN
                           ('CONNECTION_FAILURE', 'CIRCUIT_OPEN', 'BULKHEAD_FULL'))::int,
                       COALESCE(percentile_cont(0.95) WITHIN GROUP (ORDER BY latency_millis), 0)
                  FROM provider.interactions
                 WHERE provider_id = ? AND completed_at >= ? AND completed_at <= ?
                """, rs -> rs.next() ? new EvidenceCounts(
                rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4),
                rs.getBigDecimal(5).longValue()) : new EvidenceCounts(0, 0, 0, 0, 0),
                providerId, Timestamp.from(windowStart), Timestamp.from(now));
    }

    private CurrentHealth currentPointer(String providerId) {
        return jdbc.query("""
                SELECT health_version, state
                  FROM provider.health_current
                 WHERE provider_id = ?
                 FOR UPDATE
                """, rs -> rs.next() ? new CurrentHealth(
                rs.getLong("health_version"), ProviderHealthState.valueOf(rs.getString("state")))
                : null, providerId);
    }

    private void appendStateChanged(ProviderHealthState previous, ProviderHealthEvaluation evaluation) {
        String payload;
        try {
            LinkedHashMap<String, Object> value = new LinkedHashMap<>();
            value.put("providerId", evaluation.providerId());
            value.put("previousState", previous == null ? null : previous.name());
            value.put("state", evaluation.state().name());
            value.put("policyId", evaluation.policyId());
            value.put("policyVersion", evaluation.policyVersion());
            value.put("healthVersion", evaluation.healthVersion());
            value.put("completedCalls", evaluation.completedCalls());
            value.put("successfulCommunications", evaluation.successfulCommunications());
            value.put("timeoutCount", evaluation.timeoutCount());
            value.put("systemErrorCount", evaluation.systemErrorCount());
            value.put("p95LatencyMillis", evaluation.p95LatencyMillis());
            value.put("circuitState", evaluation.circuitState());
            value.put("windowStartedAt", evaluation.windowStartedAt());
            value.put("windowEndedAt", evaluation.windowEndedAt());
            value.put("evaluatedAt", evaluation.evaluatedAt());
            payload = JSON.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Provider health event could not be encoded", exception);
        }
        UUID providerAggregateId = UUID.nameUUIDFromBytes(
                ("provider:" + evaluation.providerId()).getBytes(StandardCharsets.UTF_8));
        outbox.appendOrGet(new OutboxMessageDraft(
                ProducerName.PROVIDER,
                "provider-health:" + evaluation.providerId() + ":" + evaluation.healthVersion(),
                "ProviderHealthChanged", 1, providerAggregateId, SYSTEM_TENANT_ID,
                "ledgerops.provider.lifecycle.v1", evaluation.providerId(), payload,
                evaluation.evaluationId(), evaluation.evaluationId(), evaluation.evaluatedAt()));
    }

    private ProviderHealthEvaluation mapEvaluation(ResultSet rs, int row) throws SQLException {
        return new ProviderHealthEvaluation(
                rs.getObject("evaluation_id", UUID.class), rs.getString("provider_id"),
                rs.getObject("policy_id", UUID.class), rs.getLong("policy_version"),
                rs.getLong("health_version"), ProviderHealthState.valueOf(rs.getString("state")),
                rs.getInt("completed_calls"), rs.getInt("successful_communications"),
                rs.getInt("timeout_count"), rs.getInt("system_error_count"),
                rs.getLong("p95_latency_millis"), rs.getString("circuit_state"),
                rs.getTimestamp("window_started_at").toInstant(),
                rs.getTimestamp("window_ended_at").toInstant(),
                rs.getTimestamp("evaluated_at").toInstant());
    }

    private record EvidenceCounts(
            int completedCalls,
            int successfulCommunications,
            int timeoutCount,
            int systemErrorCount,
            long p95LatencyMillis
    ) {
    }

    private record CurrentHealth(long healthVersion, ProviderHealthState state) {
    }
}
