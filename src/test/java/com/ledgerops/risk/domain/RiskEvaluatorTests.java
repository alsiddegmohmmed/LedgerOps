package com.ledgerops.risk.domain;

import com.ledgerops.risk.api.RiskConfigurationError;
import com.ledgerops.risk.api.RiskConfigurationException;
import com.ledgerops.risk.api.RiskDecision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.UUID;

class RiskEvaluatorTests {

    private static final UUID TENANT_ID = UUID.fromString(
            "c29f54ca-835f-4084-a8e0-974bf87658b0"
    );
    private static final UUID PAYMENT_ID = UUID.fromString(
            "bbbff021-9a35-4817-9851-f3c2c69dcb1f"
    );
    private static final RiskProfileId PROFILE_ID = RiskProfileId.from(
            UUID.fromString("f686a78f-dad4-4141-a156-b66a95d88168")
    );
    private static final RiskEvaluationId EVALUATION_ID = RiskEvaluationId.from(
            UUID.fromString("cbedce1b-bb08-4aec-81ee-53b0118c077c")
    );
    private static final Instant EVALUATED_AT = Instant.parse("2026-07-20T12:30:00Z");
    private static final Currency SAR = Currency.getInstance("SAR");

    private final RiskEvaluator evaluator = new RiskEvaluator();

    @Test
    void recordsAmountsBelowEqualToAndAboveTheRuleThreshold() {
        PaymentAmountThresholdRule rule = rule(1, SAR, "100.00", 25, true);

        EvaluatedRiskRule below = evaluate("99.99", profile(20, 80, rule)).ruleResults().getFirst();
        EvaluatedRiskRule equal = evaluate("100.00", profile(20, 80, rule)).ruleResults().getFirst();
        EvaluatedRiskRule above = evaluate("100.01", profile(20, 80, rule)).ruleResults().getFirst();

        assertFalse(below.triggered());
        assertEquals(0, below.appliedContribution());
        assertTrue(equal.triggered());
        assertEquals(25, equal.appliedContribution());
        assertTrue(above.triggered());
        assertEquals(25, above.appliedContribution());
    }

    @Test
    void aggregatesTriggeredContributionsAndCapsFinalScoreAtOneHundred() {
        RiskEvaluation evaluation = evaluate(
                "500.00",
                profile(
                        20,
                        80,
                        rule(1, SAR, "100.00", 60, true),
                        rule(2, SAR, "200.00", 55, true),
                        rule(3, SAR, "600.00", 10, true)
                )
        );

        assertEquals(115, evaluation.uncappedScore());
        assertEquals(100, evaluation.finalScore());
        assertEquals(RiskDecision.REJECT, evaluation.decision());
        assertEquals(List.of(60, 55, 0), evaluation.ruleResults().stream()
                .map(EvaluatedRiskRule::appliedContribution)
                .toList());
    }

    @Test
    void producesAReproducibleCleanScoreOfZero() {
        RiskEvaluation evaluation = evaluate(
                "50.00",
                profile(20, 80, rule(1, SAR, "100.00", 25, true))
        );

        assertEquals(0, evaluation.uncappedScore());
        assertEquals(0, evaluation.finalScore());
        assertEquals(RiskDecision.APPROVE, evaluation.decision());
    }

    @Test
    void producesExactApproveManualReviewAndRejectBoundaries() {
        assertEquals(RiskDecision.APPROVE, decisionForContribution(19));
        assertEquals(RiskDecision.MANUAL_REVIEW, decisionForContribution(20));
        assertEquals(RiskDecision.MANUAL_REVIEW, decisionForContribution(69));
        assertEquals(RiskDecision.REJECT, decisionForContribution(70));
    }

    @Test
    void evaluatesOnlyEnabledRulesMatchingThePaymentCurrency() {
        RiskEvaluation evaluation = evaluate(
                "500.00",
                profile(
                        20,
                        80,
                        rule(1, SAR, "100.00", 25, true),
                        rule(2, SAR, "100.00", 30, false),
                        rule(3, Currency.getInstance("USD"), "100.00", 40, true)
                )
        );

        assertEquals(1, evaluation.ruleResults().size());
        assertEquals(ruleId(1), evaluation.ruleResults().getFirst().ruleId());
        assertEquals(RiskRuleType.PAYMENT_AMOUNT_THRESHOLD,
                evaluation.ruleResults().getFirst().ruleType());
    }

    @Test
    void failsWhenNoEnabledRuleIsEligibleForThePaymentCurrency() {
        RiskProfile profile = profile(
                20,
                80,
                rule(1, SAR, "100.00", 25, false),
                rule(2, Currency.getInstance("USD"), "100.00", 25, true)
        );

        RiskConfigurationException exception = assertThrows(
                RiskConfigurationException.class,
                () -> evaluate("500.00", profile)
        );

        assertEquals(RiskConfigurationError.NO_ELIGIBLE_RULE, exception.error());
    }

    @Test
    void preservesExactProfileVersionAndImmutableRuleEvidence() {
        RiskEvaluation evaluation = evaluate(
                "100.00",
                profile(20, 80, rule(1, SAR, "100.00", 25, true))
        );

        assertEquals(EVALUATION_ID, evaluation.evaluationId());
        assertEquals(TENANT_ID, evaluation.tenantId());
        assertEquals(PAYMENT_ID, evaluation.paymentId());
        assertEquals(PROFILE_ID, evaluation.profileId());
        assertEquals(3, evaluation.profileVersion());
        assertEquals(EVALUATED_AT, evaluation.evaluatedAt());
        assertThrows(
                UnsupportedOperationException.class,
                () -> evaluation.ruleResults().clear()
        );
    }

    @Test
    void rejectsIncompleteOrInconsistentEvaluationEvidence() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new RiskEvaluation(
                        EVALUATION_ID,
                        TENANT_ID,
                        PAYMENT_ID,
                        PROFILE_ID,
                        3,
                        0,
                        0,
                        RiskDecision.APPROVE,
                        EVALUATED_AT,
                        List.of()
                )
        );

        EvaluatedRiskRule triggered = rule(1, SAR, "100.00", 25, true)
                .evaluate(new BigDecimal("100.00"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new RiskEvaluation(
                        EVALUATION_ID,
                        TENANT_ID,
                        PAYMENT_ID,
                        PROFILE_ID,
                        3,
                        24,
                        24,
                        RiskDecision.MANUAL_REVIEW,
                        EVALUATED_AT,
                        List.of(triggered)
                )
        );
    }

    @Test
    void rejectsInactiveAndCrossTenantProfilesAsTypedConfigurationErrors() {
        RiskProfile activeProfile = profile(
                20,
                80,
                rule(1, SAR, "100.00", 25, true)
        );
        RiskProfile inactiveProfile = new RiskProfile(
                activeProfile.profileId(),
                activeProfile.tenantId(),
                activeProfile.version(),
                activeProfile.reviewThreshold(),
                activeProfile.rejectThreshold(),
                false,
                activeProfile.createdAt(),
                activeProfile.rules()
        );

        RiskConfigurationException inactive = assertThrows(
                RiskConfigurationException.class,
                () -> evaluate("100.00", inactiveProfile)
        );
        RiskConfigurationException crossTenant = assertThrows(
                RiskConfigurationException.class,
                () -> evaluator.evaluate(
                        EVALUATION_ID,
                        UUID.fromString("1055efaf-cd6e-4eae-904c-7eb875e58873"),
                        PAYMENT_ID,
                        new BigDecimal("100.00"),
                        SAR,
                        activeProfile,
                        EVALUATED_AT
                )
        );

        assertEquals(RiskConfigurationError.INACTIVE_PROFILE, inactive.error());
        assertEquals(RiskConfigurationError.TENANT_PROFILE_MISMATCH, crossTenant.error());
    }

    private RiskDecision decisionForContribution(int contribution) {
        return evaluate(
                "100.00",
                profile(20, 70, rule(1, SAR, "100.00", contribution, true))
        ).decision();
    }

    private RiskEvaluation evaluate(String paymentAmount, RiskProfile profile) {
        return evaluator.evaluate(
                EVALUATION_ID,
                TENANT_ID,
                PAYMENT_ID,
                new BigDecimal(paymentAmount),
                SAR,
                profile,
                EVALUATED_AT
        );
    }

    private RiskProfile profile(
            int reviewThreshold,
            int rejectThreshold,
            PaymentAmountThresholdRule... rules
    ) {
        return new RiskProfile(
                PROFILE_ID,
                TENANT_ID,
                3,
                reviewThreshold,
                rejectThreshold,
                true,
                Instant.parse("2026-07-20T12:00:00Z"),
                List.of(rules)
        );
    }

    private PaymentAmountThresholdRule rule(
            int sequence,
            Currency currency,
            String threshold,
            int contribution,
            boolean enabled
    ) {
        return new PaymentAmountThresholdRule(
                ruleId(sequence),
                PROFILE_ID,
                currency,
                new BigDecimal(threshold),
                contribution,
                enabled
        );
    }

    private RiskRuleId ruleId(int sequence) {
        return RiskRuleId.from(UUID.fromString("00000000-0000-0000-0000-00000000000" + sequence));
    }
}
