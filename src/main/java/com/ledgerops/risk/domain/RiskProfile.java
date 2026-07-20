package com.ledgerops.risk.domain;

import com.ledgerops.risk.api.RiskConfigurationError;
import com.ledgerops.risk.api.RiskConfigurationException;
import com.ledgerops.risk.api.RiskDecision;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record RiskProfile(
        RiskProfileId profileId,
        UUID tenantId,
        long version,
        int reviewThreshold,
        int rejectThreshold,
        boolean active,
        Instant createdAt,
        List<PaymentAmountThresholdRule> rules
) {

    public RiskProfile {
        Objects.requireNonNull(profileId, "Risk profile ID must not be null");
        Objects.requireNonNull(tenantId, "Tenant ID must not be null");
        Objects.requireNonNull(createdAt, "Risk profile creation time must not be null");
        rules = List.copyOf(Objects.requireNonNull(rules, "Risk profile rules must not be null"));

        if (version < 1) {
            throw new IllegalArgumentException("Risk profile version must be positive");
        }
        if (reviewThreshold < 1
                || reviewThreshold >= rejectThreshold
                || rejectThreshold > 100) {
            throw new RiskConfigurationException(
                    RiskConfigurationError.INVALID_THRESHOLD_ORDER,
                    "Risk thresholds must satisfy 1 <= reviewThreshold < rejectThreshold <= 100"
            );
        }

        for (PaymentAmountThresholdRule rule : rules) {
            if (!profileId.equals(rule.profileId())) {
                throw new RiskConfigurationException(
                        RiskConfigurationError.PROFILE_RULE_MISMATCH,
                        "Risk rule must belong to the exact profile version"
                );
            }
        }
    }

    public RiskDecision decide(int finalScore) {
        if (finalScore < 0 || finalScore > 100) {
            throw new IllegalArgumentException("Final Risk score must be from 0 through 100");
        }
        if (finalScore < reviewThreshold) {
            return RiskDecision.APPROVE;
        }
        if (finalScore < rejectThreshold) {
            return RiskDecision.MANUAL_REVIEW;
        }
        return RiskDecision.REJECT;
    }
}
