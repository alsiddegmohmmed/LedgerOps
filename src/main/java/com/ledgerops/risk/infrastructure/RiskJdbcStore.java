package com.ledgerops.risk.infrastructure;

import com.ledgerops.risk.application.RiskEvaluationStore;
import com.ledgerops.risk.api.RiskConfigurationStore;
import com.ledgerops.risk.application.RiskProfileStore;
import com.ledgerops.risk.application.RiskConfigurationConflictException;
import com.ledgerops.risk.application.RiskReviewStore;
import com.ledgerops.risk.domain.EvaluatedRiskRule;
import com.ledgerops.risk.domain.PaymentAmountThresholdRule;
import com.ledgerops.risk.api.RiskConfigurationError;
import com.ledgerops.risk.api.RiskConfigurationException;
import com.ledgerops.risk.api.RiskDecision;
import com.ledgerops.risk.api.RiskPaymentQuery;
import com.ledgerops.risk.api.RiskPaymentSnapshot;
import com.ledgerops.risk.domain.RiskEvaluation;
import com.ledgerops.risk.domain.RiskEvaluationId;
import com.ledgerops.risk.domain.RiskProfile;
import com.ledgerops.risk.domain.RiskProfileId;
import com.ledgerops.risk.domain.RiskReview;
import com.ledgerops.risk.api.RiskReviewDecision;
import com.ledgerops.risk.api.RiskReviewId;
import com.ledgerops.risk.api.RiskReviewStatus;
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
import java.util.Set;
import java.util.UUID;

@Repository
class RiskJdbcStore implements RiskProfileStore, RiskConfigurationStore,
        RiskEvaluationStore, RiskPaymentQuery, RiskReviewStore {

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

    private static final String INSERT_REVIEW_SQL = """
            INSERT INTO risk.risk_reviews
                (id, tenant_id, payment_id, merchant_id, evaluation_id, status, assigned_analyst_id,
                 priority, sla_version, created_at, due_at, decision, decision_reason, case_id,
                 decided_at, version)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (tenant_id, payment_id) DO NOTHING
            """;
    private static final String FIND_REVIEW_SQL = """
            SELECT id, tenant_id, payment_id, merchant_id, evaluation_id, status, assigned_analyst_id,
                   priority, sla_version, created_at, due_at, decision, decision_reason, case_id,
                   decided_at, version
              FROM risk.risk_reviews
             WHERE tenant_id = ? AND id = ?
            """;
    private static final String FIND_REVIEW_FOR_UPDATE_SQL = FIND_REVIEW_SQL + " FOR UPDATE";
    private static final String FIND_REVIEW_BY_PAYMENT_SQL = """
            SELECT id, tenant_id, payment_id, merchant_id, evaluation_id, status, assigned_analyst_id,
                   priority, sla_version, created_at, due_at, decision, decision_reason, case_id,
                   decided_at, version
              FROM risk.risk_reviews
             WHERE tenant_id = ? AND payment_id = ?
            """;
    private static final String QUEUE_REVIEW_SQL = """
            SELECT id, tenant_id, payment_id, merchant_id, evaluation_id, status, assigned_analyst_id,
                   priority, sla_version, created_at, due_at, decision, decision_reason, case_id,
                   decided_at, version
              FROM risk.risk_reviews
             WHERE tenant_id = ? AND status IN ('UNASSIGNED', 'ASSIGNED')
             ORDER BY due_at ASC, priority DESC, id ASC
            """;
    private static final String UPDATE_REVIEW_SQL = """
            UPDATE risk.risk_reviews
               SET status = ?, assigned_analyst_id = ?, priority = ?, decision = ?,
                   decision_reason = ?, case_id = ?, decided_at = ?, version = ?
             WHERE tenant_id = ? AND id = ? AND version = ?
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

        return toProfile(profiles.getFirst());
    }

    @Override
    public Optional<RiskProfile> findActiveProfile(UUID tenantId) {
        List<ProfileRow> profiles = jdbcTemplate.query(
                FIND_ACTIVE_PROFILE_SQL,
                this::mapProfileRow,
                tenantId
        );
        if (profiles.size() > 1) {
            throw new RiskConfigurationException(
                    RiskConfigurationError.MULTIPLE_ACTIVE_PROFILES,
                    "Multiple active Risk profiles exist for the tenant");
        }
        return profiles.isEmpty() ? Optional.empty() : Optional.of(toProfile(profiles.getFirst()));
    }

    @Override
    public List<RiskProfile> findProfileHistory(UUID tenantId) {
        return jdbcTemplate.query(
                """
                SELECT id, tenant_id, version, review_threshold, reject_threshold, active, created_at
                  FROM risk.risk_profiles
                 WHERE tenant_id = ?
                 ORDER BY version DESC
                """,
                this::mapProfileRow,
                tenantId
        ).stream().map(this::toProfile).toList();
    }

    @Override
    @Transactional
    public RiskProfile appendActiveProfile(RiskProfile profile, Long expectedActiveVersion) {
        List<ProfileRow> current = jdbcTemplate.query(
                FIND_ACTIVE_PROFILE_SQL + " FOR UPDATE",
                this::mapProfileRow,
                profile.tenantId()
        );
        if (current.size() > 1) {
            throw new RiskConfigurationException(
                    RiskConfigurationError.MULTIPLE_ACTIVE_PROFILES,
                    "Multiple active Risk profiles exist for the tenant");
        }
        Long currentVersion = current.isEmpty() ? null : current.getFirst().version();
        if (expectedActiveVersion != null
                && !expectedActiveVersion.equals(currentVersion)) {
            throw new RiskConfigurationConflictException(
                    "Risk configuration changed; expected active version "
                            + expectedActiveVersion + " but found " + currentVersion);
        }
        if (currentVersion != null && profile.version() <= currentVersion) {
            throw new RiskConfigurationConflictException(
                    "Risk configuration version must advance beyond the active version");
        }
        if (currentVersion != null) {
            jdbcTemplate.update(
                    "UPDATE risk.risk_profiles SET active = false WHERE tenant_id = ? AND active",
                    profile.tenantId());
        }

        jdbcTemplate.update(
                INSERT_PROFILE_SQL,
                profile.profileId().value(), profile.tenantId(), profile.version(),
                profile.reviewThreshold(), profile.rejectThreshold(), profile.active(),
                Timestamp.from(profile.createdAt()));
        for (PaymentAmountThresholdRule rule : profile.rules()) {
            jdbcTemplate.update(
                    INSERT_RULE_SQL,
                    rule.ruleId().value(), profile.tenantId(), rule.profileId().value(),
                    rule.currency().getCurrencyCode(), rule.amountThreshold(),
                    rule.scoreContribution(), rule.enabled());
        }
        return profile;
    }

    private RiskProfile toProfile(ProfileRow profile) {
        List<PaymentAmountThresholdRule> rules = jdbcTemplate.query(
                FIND_PROFILE_RULES_SQL,
                this::mapRule,
                profile.tenantId(),
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

    @Override
    public RiskReview insertIfAbsent(RiskReview review) {
        int inserted = jdbcTemplate.update(INSERT_REVIEW_SQL,
                review.reviewId().value(), review.tenantId(), review.paymentId(),
                review.merchantId(), review.evaluationId(), review.status().name(), review.assignedAnalystId(),
                review.priority(), review.slaVersion(), Timestamp.from(review.createdAt()), Timestamp.from(review.dueAt()),
                review.decision() == null ? null : review.decision().name(), review.decisionReason(),
                review.caseId(), review.decidedAt() == null ? null : Timestamp.from(review.decidedAt()),
                review.version());
        if (inserted == 0) {
            return jdbcTemplate.query(FIND_REVIEW_BY_PAYMENT_SQL, this::mapReview,
                    review.tenantId(), review.paymentId()).stream().findFirst()
                    .orElseThrow(() -> new IllegalStateException("RiskReview conflict was not visible"));
        }
        return review;
    }

    @Override
    public Optional<RiskReview> findByTenantAndId(UUID tenantId, UUID reviewId) {
        return jdbcTemplate.query(FIND_REVIEW_SQL, this::mapReview, tenantId, reviewId)
                .stream().findFirst();
    }

    @Override
    public Optional<RiskReview> lockByTenantAndId(UUID tenantId, UUID reviewId) {
        return jdbcTemplate.query(FIND_REVIEW_FOR_UPDATE_SQL, this::mapReview, tenantId, reviewId)
                .stream().findFirst();
    }

    @Override
    public List<RiskReview> queue(UUID tenantId) {
        return jdbcTemplate.query(QUEUE_REVIEW_SQL, this::mapReview, tenantId);
    }

    @Override
    public List<RiskReview> queue(UUID tenantId, Set<UUID> merchantIds) {
        if (merchantIds.isEmpty()) return List.of();
        String placeholders = String.join(", ", java.util.Collections.nCopies(merchantIds.size(), "?"));
        String sql = QUEUE_REVIEW_SQL.replace(
                " WHERE tenant_id = ? AND status IN ('UNASSIGNED', 'ASSIGNED')",
                " WHERE tenant_id = ? AND merchant_id IN (" + placeholders
                        + ") AND status IN ('UNASSIGNED', 'ASSIGNED')");
        Object[] args = new Object[merchantIds.size() + 1];
        args[0] = tenantId;
        int index = 1;
        for (UUID merchantId : merchantIds) args[index++] = merchantId;
        return jdbcTemplate.query(sql, this::mapReview, args);
    }

    @Override
    public boolean update(RiskReview review, long expectedVersion) {
        return jdbcTemplate.update(UPDATE_REVIEW_SQL, review.status().name(),
                review.assignedAnalystId(), review.priority(),
                review.decision() == null ? null : review.decision().name(),
                review.decisionReason(), review.caseId(),
                review.decidedAt() == null ? null : Timestamp.from(review.decidedAt()),
                review.version(), review.tenantId(), review.reviewId().value(), expectedVersion) == 1;
    }

    @Override
    public Optional<RiskPaymentSnapshot> findSnapshotByTenantAndPayment(
            UUID tenantId,
            UUID paymentId
    ) {
        return findByTenantAndPayment(tenantId, paymentId)
                .map(evaluation -> new RiskPaymentSnapshot(
                        evaluation.evaluationId().value(),
                        evaluation.profileId().value(),
                        evaluation.profileVersion(),
                        evaluation.finalScore(),
                        evaluation.decision(),
                        evaluation.evaluatedAt()));
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

    private RiskReview mapReview(ResultSet rs, int rowNumber) throws SQLException {
        String decision = rs.getString("decision");
        Timestamp decidedAt = rs.getTimestamp("decided_at");
        return RiskReview.restore(
                RiskReviewId.from(rs.getObject("id", UUID.class)),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("payment_id", UUID.class),
                rs.getObject("merchant_id", UUID.class),
                rs.getObject("evaluation_id", UUID.class),
                RiskReviewStatus.valueOf(rs.getString("status")),
                rs.getObject("assigned_analyst_id", UUID.class),
                rs.getInt("priority"),
                rs.getInt("sla_version"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("due_at").toInstant(),
                decision == null ? null : RiskReviewDecision.valueOf(decision),
                rs.getString("decision_reason"),
                rs.getObject("case_id", UUID.class),
                decidedAt == null ? null : decidedAt.toInstant(),
                rs.getLong("version"));
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
