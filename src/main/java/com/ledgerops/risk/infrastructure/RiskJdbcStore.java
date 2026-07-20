package com.ledgerops.risk.infrastructure;

import com.ledgerops.risk.application.RiskEvaluationStore;
import com.ledgerops.risk.application.RiskProfileStore;
import com.ledgerops.risk.domain.EvaluatedRiskRule;
import com.ledgerops.risk.domain.PaymentAmountThresholdRule;
import com.ledgerops.risk.api.RiskConfigurationError;
import com.ledgerops.risk.api.RiskConfigurationException;
import com.ledgerops.risk.api.RiskDecision;
import com.ledgerops.risk.domain.RiskEvaluation;
import com.ledgerops.risk.domain.RiskEvaluationId;
import com.ledgerops.risk.domain.RiskProfile;
import com.ledgerops.risk.domain.RiskProfileId;
import com.ledgerops.risk.domain.RiskRuleId;
import com.ledgerops.risk.domain.RiskRuleType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class RiskJdbcStore implements RiskProfileStore, RiskEvaluationStore {

    private static final String INSERT_PROFILE_SQL = """
            INSERT INTO risk.risk_profiles (
                id,
                tenant_id,
                version,
                review_threshold,
                reject_threshold,
                active,
                created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String INSERT_RULE_SQL = """
            INSERT INTO risk.payment_amount_threshold_rules (
                id,
                tenant_id,
                profile_id,
                currency,
                amount_threshold,
                score_contribution,
                enabled
            ) VALUES (?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String FIND_ACTIVE_PROFILE_SQL = """
            SELECT id,
                   tenant_id,
                   version,
                   review_threshold,
                   reject_threshold,
                   active,
                   created_at
              FROM risk.risk_profiles
             WHERE tenant_id = ?
               AND active
             ORDER BY version
            """;

    private static final String FIND_PROFILE_RULES_SQL = """
            SELECT id,
                   profile_id,
                   currency,
                   amount_threshold,
                   score_contribution,
                   enabled
              FROM risk.payment_amount_threshold_rules
             WHERE tenant_id = ?
               AND profile_id = ?
             ORDER BY id
            """;

    private static final String INSERT_EVALUATION_SQL = """
            INSERT INTO risk.risk_evaluations (
                id,
                tenant_id,
                payment_id,
                profile_id,
                profile_version,
                uncapped_score,
                final_score,
                decision,
                evaluated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (tenant_id, payment_id) DO NOTHING
            """;

    private static final String INSERT_RULE_RESULT_SQL = """
            INSERT INTO risk.evaluated_rule_results (
                tenant_id,
                evaluation_id,
                rule_id,
                rule_type,
                currency,
                amount_threshold,
                configured_contribution,
                triggered,
                applied_contribution
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String FIND_EVALUATION_SQL = """
            SELECT id,
                   tenant_id,
                   payment_id,
                   profile_id,
                   profile_version,
                   uncapped_score,
                   final_score,
                   decision,
                   evaluated_at
              FROM risk.risk_evaluations
             WHERE tenant_id = ?
               AND payment_id = ?
            """;

    private static final String FIND_RULE_RESULTS_SQL = """
            SELECT rule_id,
                   rule_type,
                   currency,
                   amount_threshold,
                   configured_contribution,
                   triggered,
                   applied_contribution
              FROM risk.evaluated_rule_results
             WHERE tenant_id = ?
               AND evaluation_id = ?
             ORDER BY rule_id
            """;

    private final JdbcTemplate jdbcTemplate;

    RiskJdbcStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional
    public void insert(RiskProfile profile) {
        jdbcTemplate.update(
                INSERT_PROFILE_SQL,
                profile.profileId().value(),
                profile.tenantId(),
                profile.version(),
                profile.reviewThreshold(),
                profile.rejectThreshold(),
                profile.active(),
                Timestamp.from(profile.createdAt())
        );

        for (PaymentAmountThresholdRule rule : profile.rules()) {
            jdbcTemplate.update(
                    INSERT_RULE_SQL,
                    rule.ruleId().value(),
                    profile.tenantId(),
                    rule.profileId().value(),
                    rule.currency().getCurrencyCode(),
                    rule.amountThreshold(),
                    rule.scoreContribution(),
                    rule.enabled()
            );
        }
    }

    @Override
    public RiskProfile loadActiveProfile(UUID tenantId) {
        List<ProfileRow> profiles = jdbcTemplate.query(
                FIND_ACTIVE_PROFILE_SQL,
                this::mapProfileRow,
                tenantId
        );

        if (profiles.isEmpty()) {
            throw new RiskConfigurationException(
                    RiskConfigurationError.NO_ACTIVE_PROFILE,
                    "No active Risk profile exists for the tenant"
            );
        }
        if (profiles.size() > 1) {
            throw new RiskConfigurationException(
                    RiskConfigurationError.MULTIPLE_ACTIVE_PROFILES,
                    "Multiple active Risk profiles exist for the tenant"
            );
        }

        ProfileRow profile = profiles.getFirst();
        List<PaymentAmountThresholdRule> rules = jdbcTemplate.query(
                FIND_PROFILE_RULES_SQL,
                this::mapRule,
                tenantId,
                profile.profileId().value()
        );

        return new RiskProfile(
                profile.profileId(),
                profile.tenantId(),
                profile.version(),
                profile.reviewThreshold(),
                profile.rejectThreshold(),
                profile.active(),
                profile.createdAt().toInstant(),
                rules
        );
    }

    @Override
    @Transactional
    public RiskEvaluation appendInitialOrLoadExisting(RiskEvaluation evaluation) {
        int inserted = jdbcTemplate.update(
                INSERT_EVALUATION_SQL,
                evaluation.evaluationId().value(),
                evaluation.tenantId(),
                evaluation.paymentId(),
                evaluation.profileId().value(),
                evaluation.profileVersion(),
                evaluation.uncappedScore(),
                evaluation.finalScore(),
                evaluation.decision().name(),
                Timestamp.from(evaluation.evaluatedAt())
        );

        if (inserted == 0) {
            return findByTenantAndPayment(evaluation.tenantId(), evaluation.paymentId())
                    .orElseThrow(() -> new IllegalStateException(
                            "Conflicting Risk evaluation was not visible after insert"
                    ));
        }

        for (EvaluatedRiskRule result : evaluation.ruleResults()) {
            jdbcTemplate.update(
                    INSERT_RULE_RESULT_SQL,
                    evaluation.tenantId(),
                    evaluation.evaluationId().value(),
                    result.ruleId().value(),
                    result.ruleType().name(),
                    result.currency().getCurrencyCode(),
                    result.amountThreshold(),
                    result.configuredContribution(),
                    result.triggered(),
                    result.appliedContribution()
            );
        }

        return evaluation;
    }

    @Override
    public Optional<RiskEvaluation> findByTenantAndPayment(UUID tenantId, UUID paymentId) {
        return jdbcTemplate.query(
                        FIND_EVALUATION_SQL,
                        this::mapEvaluationRow,
                        tenantId,
                        paymentId
                )
                .stream()
                .findFirst()
                .map(row -> new RiskEvaluation(
                        row.evaluationId(),
                        row.tenantId(),
                        row.paymentId(),
                        row.profileId(),
                        row.profileVersion(),
                        row.uncappedScore(),
                        row.finalScore(),
                        row.decision(),
                        row.evaluatedAt().toInstant(),
                        loadRuleResults(row.tenantId(), row.evaluationId())
                ));
    }

    private List<EvaluatedRiskRule> loadRuleResults(
            UUID tenantId,
            RiskEvaluationId evaluationId
    ) {
        return jdbcTemplate.query(
                FIND_RULE_RESULTS_SQL,
                this::mapRuleResult,
                tenantId,
                evaluationId.value()
        );
    }

    private ProfileRow mapProfileRow(ResultSet resultSet, int rowNumber) throws SQLException {
        return new ProfileRow(
                RiskProfileId.from(resultSet.getObject("id", UUID.class)),
                resultSet.getObject("tenant_id", UUID.class),
                resultSet.getLong("version"),
                resultSet.getInt("review_threshold"),
                resultSet.getInt("reject_threshold"),
                resultSet.getBoolean("active"),
                resultSet.getTimestamp("created_at")
        );
    }

    private PaymentAmountThresholdRule mapRule(
            ResultSet resultSet,
            int rowNumber
    ) throws SQLException {
        return new PaymentAmountThresholdRule(
                RiskRuleId.from(resultSet.getObject("id", UUID.class)),
                RiskProfileId.from(resultSet.getObject("profile_id", UUID.class)),
                Currency.getInstance(resultSet.getString("currency")),
                resultSet.getBigDecimal("amount_threshold"),
                resultSet.getInt("score_contribution"),
                resultSet.getBoolean("enabled")
        );
    }

    private EvaluationRow mapEvaluationRow(
            ResultSet resultSet,
            int rowNumber
    ) throws SQLException {
        return new EvaluationRow(
                RiskEvaluationId.from(resultSet.getObject("id", UUID.class)),
                resultSet.getObject("tenant_id", UUID.class),
                resultSet.getObject("payment_id", UUID.class),
                RiskProfileId.from(resultSet.getObject("profile_id", UUID.class)),
                resultSet.getLong("profile_version"),
                resultSet.getLong("uncapped_score"),
                resultSet.getInt("final_score"),
                RiskDecision.valueOf(resultSet.getString("decision")),
                resultSet.getTimestamp("evaluated_at")
        );
    }

    private EvaluatedRiskRule mapRuleResult(
            ResultSet resultSet,
            int rowNumber
    ) throws SQLException {
        return new EvaluatedRiskRule(
                RiskRuleId.from(resultSet.getObject("rule_id", UUID.class)),
                RiskRuleType.valueOf(resultSet.getString("rule_type")),
                Currency.getInstance(resultSet.getString("currency")),
                resultSet.getBigDecimal("amount_threshold"),
                resultSet.getInt("configured_contribution"),
                resultSet.getBoolean("triggered"),
                resultSet.getInt("applied_contribution")
        );
    }

    private record ProfileRow(
            RiskProfileId profileId,
            UUID tenantId,
            long version,
            int reviewThreshold,
            int rejectThreshold,
            boolean active,
            Timestamp createdAt
    ) {
    }

    private record EvaluationRow(
            RiskEvaluationId evaluationId,
            UUID tenantId,
            UUID paymentId,
            RiskProfileId profileId,
            long profileVersion,
            long uncappedScore,
            int finalScore,
            RiskDecision decision,
            Timestamp evaluatedAt
    ) {
    }
}
