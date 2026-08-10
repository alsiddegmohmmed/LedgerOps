package com.ledgerops.provider.infrastructure;

import com.ledgerops.provider.api.ProviderScenarioAssignment;
import com.ledgerops.provider.api.ProviderScenarioPort;
import com.ledgerops.provider.api.ProviderScenarioProfile;
import com.ledgerops.provider.api.ProviderScenarioScope;
import com.ledgerops.provider.api.ProviderScenarioSnapshot;
import com.ledgerops.provider.api.ProviderSettlementMode;
import com.ledgerops.provider.api.ProviderSubmissionOutcome;
import com.ledgerops.provider.api.ProviderWebhookMode;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.JsonNode;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Repository
class JdbcProviderScenarioStore implements ProviderScenarioPort {

    private static final UUID DEFAULT_PROFILE_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000005");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final JdbcTemplate jdbc;
    private final Clock clock;

    JdbcProviderScenarioStore(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    @Transactional
    public ProviderScenarioProfile createProfile(
            ProviderScenarioProfile requested,
            Long expectedPreviousVersion
    ) {
        Objects.requireNonNull(requested, "Scenario profile must not be null");
        Long existing = jdbc.query(
                "SELECT max(version) FROM provider.scenario_profiles WHERE profile_id = ?",
                rs -> rs.next() && rs.getObject(1) != null ? rs.getLong(1) : null,
                requested.profileId());
        if (expectedPreviousVersion != null
                && !expectedPreviousVersion.equals(existing)) {
            throw new ProviderScenarioConflictException(
                    "Scenario profile version changed; expected "
                            + expectedPreviousVersion + " but found " + existing);
        }
        if (existing != null && requested.version() <= existing) {
            throw new ProviderScenarioConflictException(
                    "Scenario profile version must advance beyond the existing version");
        }
        try {
            jdbc.update(
                    """
                    INSERT INTO provider.scenario_profiles
                        (profile_id, version, submission_outcome, webhook_mode, settlement_mode,
                         delay_millis, fixture_id, parameters, canonical_snapshot, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?)
                    """,
                    requested.profileId(), requested.version(), requested.submissionOutcome().name(),
                    requested.webhookMode().name(), requested.settlementMode().name(),
                    requested.delayMillis(), requested.fixtureId(), json(requested.parameters()),
                    canonical(requested), Timestamp.from(requested.createdAt()));
        } catch (DataIntegrityViolationException exception) {
            throw new ProviderScenarioConflictException(
                    "Scenario profile version already exists or violates an assignment constraint");
        }
        return requested;
    }

    @Override
    @Transactional
    public ProviderScenarioAssignment assign(
            ProviderScenarioScope scope,
            UUID tenantId,
            UUID paymentId,
            UUID profileId,
            long profileVersion
    ) {
        findProfile(profileId, profileVersion).orElseThrow(() ->
                new IllegalArgumentException("Scenario profile version does not exist"));
        String targetPredicate = switch (scope) {
            case GLOBAL -> "scope = 'GLOBAL'";
            case TENANT -> "scope = 'TENANT' AND tenant_id = ?";
            case PAYMENT -> "scope = 'PAYMENT' AND tenant_id = ? AND payment_id = ?";
        };
        Object[] updateArgs = switch (scope) {
            case GLOBAL -> new Object[0];
            case TENANT -> new Object[]{tenantId};
            case PAYMENT -> new Object[]{tenantId, paymentId};
        };
        jdbc.update("UPDATE provider.scenario_assignments SET active = false WHERE active AND "
                + targetPredicate, updateArgs);
        ProviderScenarioAssignment assignment = new ProviderScenarioAssignment(
                UUID.randomUUID(), scope, tenantId, paymentId, profileId, profileVersion,
                true, clock.instant());
        jdbc.update(
                """
                INSERT INTO provider.scenario_assignments
                    (assignment_id, scope, tenant_id, payment_id, profile_id, profile_version,
                     active, created_at)
                VALUES (?, ?, ?, ?, ?, ?, true, ?)
                """,
                assignment.assignmentId(), assignment.scope().name(), assignment.tenantId(),
                assignment.paymentId(), assignment.profileId(), assignment.profileVersion(),
                Timestamp.from(assignment.createdAt()));
        return assignment;
    }

    @Override
    public Optional<ProviderScenarioProfile> findProfile(UUID profileId, long version) {
        return jdbc.query(
                """
                SELECT profile_id, version, submission_outcome, webhook_mode, settlement_mode,
                       delay_millis, fixture_id, parameters::text, created_at
                  FROM provider.scenario_profiles
                 WHERE profile_id = ? AND version = ?
                """,
                this::mapProfile, profileId, version).stream().findFirst();
    }

    @Override
    public List<ProviderScenarioAssignment> assignments() {
        return jdbc.query(
                """
                SELECT assignment_id, scope, tenant_id, payment_id, profile_id, profile_version,
                       active, created_at
                  FROM provider.scenario_assignments
                 WHERE active
                 ORDER BY scope, tenant_id NULLS FIRST, payment_id NULLS FIRST, created_at DESC
                """,
                this::mapAssignment);
    }

    @Override
    @Transactional
    public ProviderScenarioSnapshot resolveAndPin(
            UUID tenantId,
            UUID paymentId,
            String operationType,
            Instant now
    ) {
        Optional<ProviderScenarioSnapshot> existing = findPin(tenantId, paymentId, operationType);
        if (existing.isPresent()) return existing.orElseThrow();
        ScenarioProfileRow selected = jdbc.query(
                """
                SELECT p.profile_id, p.version, p.submission_outcome, p.webhook_mode,
                       p.settlement_mode, p.delay_millis, p.fixture_id, p.parameters::text,
                       p.created_at
                  FROM provider.scenario_assignments a
                  JOIN provider.scenario_profiles p
                    ON p.profile_id = a.profile_id AND p.version = a.profile_version
                 WHERE a.active
                   AND ((a.scope = 'PAYMENT' AND a.tenant_id = ? AND a.payment_id = ?)
                     OR (a.scope = 'TENANT' AND a.tenant_id = ?)
                     OR a.scope = 'GLOBAL')
                 ORDER BY CASE a.scope WHEN 'PAYMENT' THEN 1 WHEN 'TENANT' THEN 2 ELSE 3 END
                 LIMIT 1
                """,
                this::mapScenarioRow, tenantId, paymentId, tenantId).stream().findFirst()
                .orElseGet(() -> jdbc.query(
                        """
                        SELECT profile_id, version, submission_outcome, webhook_mode,
                               settlement_mode, delay_millis, fixture_id, parameters::text,
                               created_at
                          FROM provider.scenario_profiles
                         WHERE profile_id = ? AND version = 1
                        """,
                        this::mapScenarioRow, DEFAULT_PROFILE_ID).stream().findFirst()
                        .orElseThrow(() -> new IllegalStateException("Default Provider scenario is missing")));
        ProviderScenarioProfile profile = selected.profile();
        ProviderScenarioSnapshot snapshot = ProviderScenarioSnapshot.from(profile, now);
        jdbc.update(
                """
                INSERT INTO provider.scenario_pins
                    (tenant_id, operation_type, operation_id, profile_id, profile_version,
                     canonical_snapshot, pinned_at)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)
                ON CONFLICT (tenant_id, operation_type, operation_id) DO NOTHING
                """,
                tenantId, operationType, paymentId, snapshot.profileId(), snapshot.profileVersion(),
                canonical(snapshot), Timestamp.from(now));
        return findPin(tenantId, paymentId, operationType).orElseThrow(() ->
                new IllegalStateException("Provider scenario pin was not durable"));
    }

    @Override
    public Optional<ProviderScenarioSnapshot> findPin(
            UUID tenantId,
            UUID paymentId,
            String operationType
    ) {
        return jdbc.query(
                """
                SELECT profile_id, profile_version, canonical_snapshot::text, pinned_at
                  FROM provider.scenario_pins
                 WHERE tenant_id = ? AND operation_type = ? AND operation_id = ?
                """,
                (rs, row) -> mapSnapshot(rs.getObject("profile_id", UUID.class),
                        rs.getLong("profile_version"), rs.getString("canonical_snapshot"),
                        rs.getTimestamp("pinned_at").toInstant()),
                tenantId, operationType, paymentId).stream().findFirst();
    }

    private ProviderScenarioSnapshot mapSnapshot(
            UUID profileId,
            long profileVersion,
            String canonicalSnapshot,
            Instant pinnedAt
    ) {
        try {
            JsonNode node = JSON.readTree(canonicalSnapshot);
            return new ProviderScenarioSnapshot(
                    profileId,
                    profileVersion,
                    ProviderSubmissionOutcome.valueOf(node.path("submissionOutcome").asString()),
                    ProviderWebhookMode.valueOf(node.path("webhookMode").asString()),
                    ProviderSettlementMode.valueOf(node.path("settlementMode").asString()),
                    node.path("delayMillis").asLong(),
                    node.hasNonNull("fixtureId") ? node.get("fixtureId").asString() : null,
                    readMap(node.path("parameters").toString()),
                    pinnedAt);
        } catch (Exception exception) {
            throw new IllegalStateException("Provider scenario pin is not valid JSON", exception);
        }
    }

    private ProviderScenarioProfile mapProfile(ResultSet rs, int row) throws SQLException {
        return new ProviderScenarioProfile(
                rs.getObject("profile_id", UUID.class), rs.getLong("version"),
                ProviderSubmissionOutcome.valueOf(rs.getString("submission_outcome")),
                ProviderWebhookMode.valueOf(rs.getString("webhook_mode")),
                ProviderSettlementMode.valueOf(rs.getString("settlement_mode")),
                rs.getLong("delay_millis"), rs.getString("fixture_id"),
                readMap(rs.getString("parameters")), rs.getTimestamp("created_at").toInstant());
    }

    private ScenarioProfileRow mapScenarioRow(ResultSet rs, int row) throws SQLException {
        return new ScenarioProfileRow(mapProfile(rs, row));
    }

    private ProviderScenarioAssignment mapAssignment(ResultSet rs, int row) throws SQLException {
        return new ProviderScenarioAssignment(
                rs.getObject("assignment_id", UUID.class),
                ProviderScenarioScope.valueOf(rs.getString("scope")),
                rs.getObject("tenant_id", UUID.class), rs.getObject("payment_id", UUID.class),
                rs.getObject("profile_id", UUID.class), rs.getLong("profile_version"),
                rs.getBoolean("active"), rs.getTimestamp("created_at").toInstant());
    }

    private Map<String, String> readMap(String value) {
        try {
            @SuppressWarnings("unchecked") Map<String, String> result = JSON.readValue(value, Map.class);
            return result == null ? Map.of() : result;
        } catch (Exception exception) {
            throw new IllegalStateException("Scenario parameters are not valid JSON", exception);
        }
    }

    private String json(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Scenario JSON could not be serialized", exception);
        }
    }

    private String canonical(ProviderScenarioProfile profile) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("profileId", profile.profileId());
        value.put("version", profile.version());
        value.put("submissionOutcome", profile.submissionOutcome().name());
        value.put("webhookMode", profile.webhookMode().name());
        value.put("settlementMode", profile.settlementMode().name());
        value.put("delayMillis", profile.delayMillis());
        value.put("fixtureId", profile.fixtureId());
        value.put("parameters", profile.parameters());
        return json(value);
    }

    private String canonical(ProviderScenarioSnapshot snapshot) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("profileId", snapshot.profileId());
        value.put("version", snapshot.profileVersion());
        value.put("submissionOutcome", snapshot.submissionOutcome().name());
        value.put("webhookMode", snapshot.webhookMode().name());
        value.put("settlementMode", snapshot.settlementMode().name());
        value.put("delayMillis", snapshot.delayMillis());
        value.put("fixtureId", snapshot.fixtureId());
        value.put("parameters", snapshot.parameters());
        return json(value);
    }

    private record ScenarioProfileRow(ProviderScenarioProfile profile) {
    }
}
