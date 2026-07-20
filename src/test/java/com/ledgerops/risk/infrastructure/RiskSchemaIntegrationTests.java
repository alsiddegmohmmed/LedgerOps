package com.ledgerops.risk.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ledgerops.support.PostgresTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
class RiskSchemaIntegrationTests {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void createsRiskOwnedProfileRuleEvaluationAndEvidenceTables() {
        List<String> tables = jdbcTemplate.queryForList(
                """
                SELECT table_name
                  FROM information_schema.tables
                 WHERE table_schema = 'risk'
                 ORDER BY table_name
                """,
                String.class
        );

        assertEquals(
                List.of(
                        "evaluated_rule_results",
                        "payment_amount_threshold_rules",
                        "risk_evaluations",
                        "risk_profiles"
                ),
                tables
        );
    }

    @Test
    void enforcesOneActiveProfilePerTenantAndValidProfileThresholds() {
        UUID tenantId = UUID.randomUUID();
        insertProfile(UUID.randomUUID(), tenantId, 1, 20, 80, true);

        assertThrows(
                DataIntegrityViolationException.class,
                () -> insertProfile(UUID.randomUUID(), tenantId, 2, 30, 90, true)
        );
        insertProfile(UUID.randomUUID(), tenantId, 2, 30, 90, false);

        assertThrows(
                DataIntegrityViolationException.class,
                () -> insertProfile(UUID.randomUUID(), UUID.randomUUID(), 1, 0, 80, true)
        );
        assertThrows(
                DataIntegrityViolationException.class,
                () -> insertProfile(UUID.randomUUID(), UUID.randomUUID(), 1, 80, 80, true)
        );
    }

    @Test
    void enforcesTheOnlyApprovedRuleConfiguration() {
        UUID tenantId = UUID.randomUUID();
        UUID profileId = UUID.randomUUID();
        insertProfile(profileId, tenantId, 1, 20, 80, true);

        assertThrows(
                DataIntegrityViolationException.class,
                () -> insertRule(UUID.randomUUID(), tenantId, profileId, "SAR", "0", 10, true)
        );
        assertThrows(
                DataIntegrityViolationException.class,
                () -> insertRule(UUID.randomUUID(), tenantId, profileId, "SAR", "100", 0, true)
        );
        assertThrows(
                DataIntegrityViolationException.class,
                () -> insertRule(UUID.randomUUID(), tenantId, profileId, "SAR", "100", 101, true)
        );
    }

    @Test
    void preservesVersionedProfileAndRuleHistoryWhileAllowingDeactivation() {
        UUID tenantId = UUID.randomUUID();
        UUID profileId = UUID.randomUUID();
        UUID ruleId = UUID.randomUUID();
        insertProfile(profileId, tenantId, 1, 20, 80, true);
        insertRule(ruleId, tenantId, profileId, "SAR", "100", 25, true);

        assertThrows(
                DataAccessException.class,
                () -> jdbcTemplate.update(
                        "UPDATE risk.risk_profiles SET review_threshold = 30 WHERE id = ?",
                        profileId
                )
        );
        assertThrows(
                DataAccessException.class,
                () -> jdbcTemplate.update(
                        "UPDATE risk.payment_amount_threshold_rules SET score_contribution = 30 WHERE id = ?",
                        ruleId
                )
        );

        assertEquals(
                1,
                jdbcTemplate.update(
                        "UPDATE risk.risk_profiles SET active = false WHERE id = ?",
                        profileId
                )
        );
    }

    @Test
    void enforcesOneInitialEvaluationPerTenantAndPaymentWithoutAPaymentForeignKey() {
        UUID tenantId = UUID.randomUUID();
        UUID profileId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        insertProfile(profileId, tenantId, 1, 20, 80, true);
        insertEvaluation(UUID.randomUUID(), tenantId, paymentId, profileId, 1, 0, 0, "APPROVE");

        assertThrows(
                DataIntegrityViolationException.class,
                () -> insertEvaluation(
                        UUID.randomUUID(),
                        tenantId,
                        paymentId,
                        profileId,
                        1,
                        0,
                        0,
                        "APPROVE"
                )
        );

        List<String> referencedSchemas = jdbcTemplate.queryForList(
                """
                SELECT DISTINCT target_namespace.nspname
                  FROM pg_constraint constraint_record
                  JOIN pg_class source_table
                    ON source_table.oid = constraint_record.conrelid
                  JOIN pg_namespace source_namespace
                    ON source_namespace.oid = source_table.relnamespace
                  JOIN pg_class target_table
                    ON target_table.oid = constraint_record.confrelid
                  JOIN pg_namespace target_namespace
                    ON target_namespace.oid = target_table.relnamespace
                 WHERE source_namespace.nspname = 'risk'
                   AND constraint_record.contype = 'f'
                """,
                String.class
        );

        assertEquals(List.of("risk"), referencedSchemas);
    }

    @Test
    void rejectsInvalidEvaluationEvidenceAndAnyEvidenceMutation() {
        UUID tenantId = UUID.randomUUID();
        UUID profileId = UUID.randomUUID();
        UUID ruleId = UUID.randomUUID();
        UUID evaluationId = UUID.randomUUID();
        insertProfile(profileId, tenantId, 1, 20, 80, true);
        insertRule(ruleId, tenantId, profileId, "SAR", "100", 25, true);

        assertThrows(
                DataIntegrityViolationException.class,
                () -> insertEvaluation(
                        UUID.randomUUID(),
                        tenantId,
                        UUID.randomUUID(),
                        profileId,
                        1,
                        125,
                        99,
                        "REJECT"
                )
        );

        insertEvaluation(
                evaluationId,
                tenantId,
                UUID.randomUUID(),
                profileId,
                1,
                25,
                25,
                "MANUAL_REVIEW"
        );
        insertRuleResult(tenantId, evaluationId, ruleId, true, 25);

        assertThrows(
                DataAccessException.class,
                () -> jdbcTemplate.update(
                        "UPDATE risk.risk_evaluations SET final_score = 26 WHERE id = ?",
                        evaluationId
                )
        );
        assertThrows(
                DataAccessException.class,
                () -> jdbcTemplate.update(
                        "DELETE FROM risk.evaluated_rule_results WHERE evaluation_id = ?",
                        evaluationId
                )
        );

        Integer evidenceCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM risk.evaluated_rule_results WHERE evaluation_id = ?",
                Integer.class,
                evaluationId
        );
        assertEquals(1, evidenceCount);
    }

    private void insertProfile(
            UUID profileId,
            UUID tenantId,
            long version,
            int reviewThreshold,
            int rejectThreshold,
            boolean active
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO risk.risk_profiles (
                    id, tenant_id, version, review_threshold, reject_threshold, active, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                profileId,
                tenantId,
                version,
                reviewThreshold,
                rejectThreshold,
                active,
                Timestamp.from(Instant.parse("2026-07-20T12:00:00Z"))
        );
    }

    private void insertRule(
            UUID ruleId,
            UUID tenantId,
            UUID profileId,
            String currency,
            String threshold,
            int contribution,
            boolean enabled
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO risk.payment_amount_threshold_rules (
                    id, tenant_id, profile_id, currency, amount_threshold,
                    score_contribution, enabled
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                ruleId,
                tenantId,
                profileId,
                currency,
                new BigDecimal(threshold),
                contribution,
                enabled
        );
    }

    private void insertEvaluation(
            UUID evaluationId,
            UUID tenantId,
            UUID paymentId,
            UUID profileId,
            long profileVersion,
            long uncappedScore,
            int finalScore,
            String decision
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO risk.risk_evaluations (
                    id, tenant_id, payment_id, profile_id, profile_version,
                    uncapped_score, final_score, decision, evaluated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                evaluationId,
                tenantId,
                paymentId,
                profileId,
                profileVersion,
                uncappedScore,
                finalScore,
                decision,
                Timestamp.from(Instant.parse("2026-07-20T12:30:00Z"))
        );
    }

    private void insertRuleResult(
            UUID tenantId,
            UUID evaluationId,
            UUID ruleId,
            boolean triggered,
            int appliedContribution
    ) {
        jdbcTemplate.update(
                """
                INSERT INTO risk.evaluated_rule_results (
                    tenant_id, evaluation_id, rule_id, rule_type, currency,
                    amount_threshold, configured_contribution, triggered, applied_contribution
                ) VALUES (?, ?, ?, 'PAYMENT_AMOUNT_THRESHOLD', 'SAR', 100, 25, ?, ?)
                """,
                tenantId,
                evaluationId,
                ruleId,
                triggered,
                appliedContribution
        );
    }
}
