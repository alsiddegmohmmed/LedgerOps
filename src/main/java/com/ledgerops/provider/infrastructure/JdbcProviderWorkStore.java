package com.ledgerops.provider.infrastructure;

import com.ledgerops.provider.application.ProviderSubmissionCommand;
import com.ledgerops.provider.application.ProviderWorkConsistencyException;
import com.ledgerops.provider.application.ProviderWorkStore;
import com.ledgerops.provider.api.ProviderScenarioPort;
import com.ledgerops.provider.api.ProviderScenarioSnapshot;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.node.ObjectNode;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Component
class JdbcProviderWorkStore implements ProviderWorkStore {

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final ProviderScenarioPort scenarios;

    @Autowired
    JdbcProviderWorkStore(JdbcTemplate jdbc, Clock clock, ProviderScenarioPort scenarios) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.scenarios = scenarios;
    }

    JdbcProviderWorkStore(JdbcTemplate jdbc, Clock clock) {
        this(jdbc, clock, null);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void createOrVerifySubmission(ProviderSubmissionCommand command) {
        Instant now = clock.instant();
        ScenarioBinding scenario = scenarios == null ? null : pinScenario(command, now);
        String canonicalPayload = scenario == null
                ? command.canonicalPayload() : enrichPayload(command.canonicalPayload(), scenario.snapshot());
        jdbc.update("""
                INSERT INTO provider.work
                    (id, tenant_id, attempt_id, payment_id, work_type, status,
                     attempt_sequence, provider_id, provider_idempotency_key, request_intent_hash,
                     command_payload, scenario_profile_id, scenario_profile_version, scenario_snapshot,
                     due_at, correlation_id, causation_id,
                     traceparent, tracestate, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'SUBMISSION', 'PENDING', ?, ?, ?, ?, ?, ?, ?, ?::jsonb,
                        ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, attempt_id, work_type) DO NOTHING
                """, UUID.randomUUID(), command.tenantId(), command.attemptId(),
                command.paymentId(), command.attemptSequence(), command.providerId(),
                command.providerIdempotencyKey(),
                command.requestIntentHash(), canonicalPayload,
                scenario == null ? null : scenario.snapshot().profileId(),
                scenario == null ? null : scenario.snapshot().profileVersion(),
                scenario == null ? null : canonicalSnapshot(scenario.snapshot()),
                Timestamp.from(now),
                command.correlationId(), command.messageId(),
                command.traceparent(), command.tracestate(), Timestamp.from(now),
                Timestamp.from(now));

        Boolean matches = jdbc.query("""
                SELECT payment_id = ?
                   AND attempt_sequence = ?
                   AND provider_id = ?
                   AND provider_idempotency_key = ?
                   AND request_intent_hash = ?
                   AND command_payload = ?
                   AND traceparent IS NOT DISTINCT FROM ?
                   AND tracestate IS NOT DISTINCT FROM ?
                  FROM provider.work
                 WHERE tenant_id = ? AND attempt_id = ? AND work_type = 'SUBMISSION'
                """, rs -> rs.next() && rs.getBoolean(1),
                command.paymentId(), command.attemptSequence(), command.providerId(),
                command.providerIdempotencyKey(),
                command.requestIntentHash(), canonicalPayload, command.traceparent(),
                command.tracestate(), command.tenantId(),
                command.attemptId());
        if (!Boolean.TRUE.equals(matches)) {
            throw new ProviderWorkConsistencyException(
                    "Provider work identity was reused with different command content"
            );
        }
    }

    private ScenarioBinding pinScenario(ProviderSubmissionCommand command, Instant now) {
        ProviderScenarioSnapshot snapshot = scenarios.resolveAndPin(
                command.tenantId(), command.paymentId(), "PAYMENT", now);
        return new ScenarioBinding(snapshot);
    }

    private String enrichPayload(String original, ProviderScenarioSnapshot snapshot) {
        try {
            JsonMapper json = JsonMapper.builder().build();
            if (!(json.readTree(original) instanceof ObjectNode payload)) {
                throw new IllegalArgumentException("Provider command payload must be a JSON object");
            }
            payload.put("contractVersion", 2);
            payload.put("scenarioProfileId", snapshot.profileId().toString());
            payload.put("scenarioProfileVersion", snapshot.profileVersion());
            payload.set("scenarioSnapshot", json.readTree(canonicalSnapshot(snapshot)));
            return payload.toString();
        } catch (Exception exception) {
            throw new IllegalArgumentException("Provider command payload is not valid JSON", exception);
        }
    }

    private String canonicalSnapshot(ProviderScenarioSnapshot snapshot) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("profileId", snapshot.profileId());
        value.put("version", snapshot.profileVersion());
        value.put("submissionOutcome", snapshot.submissionOutcome().name());
        value.put("webhookMode", snapshot.webhookMode().name());
        value.put("settlementMode", snapshot.settlementMode().name());
        value.put("delayMillis", snapshot.delayMillis());
        value.put("fixtureId", snapshot.fixtureId());
        value.put("parameters", snapshot.parameters());
        try {
            return JsonMapper.builder().build().writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Provider scenario snapshot could not be serialized", exception);
        }
    }

    private record ScenarioBinding(ProviderScenarioSnapshot snapshot) {
    }
}
