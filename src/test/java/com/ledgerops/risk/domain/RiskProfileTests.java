package com.ledgerops.risk.domain;

import com.ledgerops.risk.api.RiskConfigurationError;
import com.ledgerops.risk.api.RiskConfigurationException;
import com.ledgerops.risk.api.RiskDecision;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.UUID;

class RiskProfileTests {

    private static final UUID TENANT_ID = UUID.fromString(
            "c29f54ca-835f-4084-a8e0-974bf87658b0"
    );
    private static final RiskProfileId PROFILE_ID = RiskProfileId.from(
            UUID.fromString("f686a78f-dad4-4141-a156-b66a95d88168")
    );

    @Test
    void containsExactlyTheApprovedRuleTypeAndDecisions() {
        assertArrayEquals(
                new RiskRuleType[]{RiskRuleType.PAYMENT_AMOUNT_THRESHOLD},
                RiskRuleType.values()
        );
        assertArrayEquals(
                new RiskDecision[]{
                    RiskDecision.APPROVE,
                    RiskDecision.MANUAL_REVIEW,
                    RiskDecision.REJECT
                },
                RiskDecision.values()
        );
    }

    @Test
    void requiresPositiveAmountThreshold() {
        for (String invalid : List.of("0.00", "-0.01")) {
            RiskConfigurationException exception = assertThrows(
                    RiskConfigurationException.class,
                    () -> rule(invalid, 10)
            );

            assertEquals(
                    RiskConfigurationError.INVALID_AMOUNT_THRESHOLD,
                    exception.error()
            );
        }
    }

    @Test
    void requiresContributionFromOneThroughOneHundred() {
        for (int invalid : List.of(0, -1, 101)) {
            RiskConfigurationException exception = assertThrows(
                    RiskConfigurationException.class,
                    () -> rule("100.00", invalid)
            );

            assertEquals(
                    RiskConfigurationError.INVALID_SCORE_CONTRIBUTION,
                    exception.error()
            );
        }
    }

    @Test
    void requiresExactThresholdOrdering() {
        for (Thresholds invalid : List.of(
                new Thresholds(0, 80),
                new Thresholds(20, 20),
                new Thresholds(80, 20),
                new Thresholds(20, 101)
        )) {
            RiskConfigurationException exception = assertThrows(
                    RiskConfigurationException.class,
                    () -> profile(invalid.review(), invalid.reject(), List.of())
            );

            assertEquals(
                    RiskConfigurationError.INVALID_THRESHOLD_ORDER,
                    exception.error()
            );
        }
    }

    @Test
    void requiresEveryRuleToBelongToTheExactProfile() {
        PaymentAmountThresholdRule foreignRule = new PaymentAmountThresholdRule(
                RiskRuleId.from(UUID.fromString("49cdf841-68f1-4cdd-8554-a503f6f8fd2e")),
                RiskProfileId.from(UUID.fromString("157ce5c9-69af-4423-b2ef-32d34879dba1")),
                Currency.getInstance("SAR"),
                new BigDecimal("100.00"),
                10,
                true
        );

        RiskConfigurationException exception = assertThrows(
                RiskConfigurationException.class,
                () -> profile(20, 80, List.of(foreignRule))
        );

        assertEquals(RiskConfigurationError.PROFILE_RULE_MISMATCH, exception.error());
    }

    @Test
    void appliesTheExactInclusiveAndExclusiveDecisionBoundaries() {
        RiskProfile profile = profile(20, 70, List.of());

        assertEquals(RiskDecision.APPROVE, profile.decide(0));
        assertEquals(RiskDecision.APPROVE, profile.decide(19));
        assertEquals(RiskDecision.MANUAL_REVIEW, profile.decide(20));
        assertEquals(RiskDecision.MANUAL_REVIEW, profile.decide(69));
        assertEquals(RiskDecision.REJECT, profile.decide(70));
        assertEquals(RiskDecision.REJECT, profile.decide(100));
    }

    private PaymentAmountThresholdRule rule(String threshold, int contribution) {
        return new PaymentAmountThresholdRule(
                RiskRuleId.from(UUID.fromString("49cdf841-68f1-4cdd-8554-a503f6f8fd2e")),
                PROFILE_ID,
                Currency.getInstance("SAR"),
                new BigDecimal(threshold),
                contribution,
                true
        );
    }

    private RiskProfile profile(
            int reviewThreshold,
            int rejectThreshold,
            List<PaymentAmountThresholdRule> rules
    ) {
        return new RiskProfile(
                PROFILE_ID,
                TENANT_ID,
                3,
                reviewThreshold,
                rejectThreshold,
                true,
                Instant.parse("2026-07-20T12:00:00Z"),
                rules
        );
    }

    private record Thresholds(int review, int reject) {
    }
}
