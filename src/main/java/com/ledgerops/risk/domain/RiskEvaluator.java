package com.ledgerops.risk.domain;

import com.ledgerops.risk.api.RiskConfigurationError;
import com.ledgerops.risk.api.RiskConfigurationException;
import com.ledgerops.risk.api.RiskDecision;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class RiskEvaluator {

    public RiskEvaluation evaluate(
            RiskEvaluationId evaluationId,
            UUID tenantId,
            UUID paymentId,
            BigDecimal paymentAmount,
            Currency paymentCurrency,
            RiskProfile profile,
            Instant evaluatedAt
    ) {
        Objects.requireNonNull(evaluationId, "Risk evaluation ID must not be null");
        Objects.requireNonNull(tenantId, "Tenant ID must not be null");
        Objects.requireNonNull(paymentId, "Payment ID must not be null");
        Objects.requireNonNull(paymentAmount, "Payment amount must not be null");
        Objects.requireNonNull(paymentCurrency, "Payment currency must not be null");
        Objects.requireNonNull(profile, "Risk profile must not be null");
        Objects.requireNonNull(evaluatedAt, "Risk evaluation time must not be null");

        requireUsableProfile(tenantId, profile);

        List<EvaluatedRiskRule> ruleResults = new ArrayList<>();
        long uncappedScore = 0;

        for (PaymentAmountThresholdRule rule : profile.rules()) {
            if (rule.isEligible(paymentCurrency)) {
                EvaluatedRiskRule result = rule.evaluate(paymentAmount);
                ruleResults.add(result);
                uncappedScore = Math.addExact(uncappedScore, result.appliedContribution());
            }
        }

        if (ruleResults.isEmpty()) {
            throw new RiskConfigurationException(
                    RiskConfigurationError.NO_ELIGIBLE_RULE,
                    "At least one enabled Risk rule must be eligible for the Payment currency"
            );
        }

        int finalScore = (int) Math.min(100L, uncappedScore);

        return new RiskEvaluation(
                evaluationId,
                tenantId,
                paymentId,
                profile.profileId(),
                profile.version(),
                uncappedScore,
                finalScore,
                profile.decide(finalScore),
                evaluatedAt,
                ruleResults
        );
    }

    private void requireUsableProfile(UUID tenantId, RiskProfile profile) {
        if (!profile.active()) {
            throw new RiskConfigurationException(
                    RiskConfigurationError.INACTIVE_PROFILE,
                    "Risk profile must be active"
            );
        }
        if (!tenantId.equals(profile.tenantId())) {
            throw new RiskConfigurationException(
                    RiskConfigurationError.TENANT_PROFILE_MISMATCH,
                    "Risk profile must belong to the Payment tenant"
            );
        }
    }
}
