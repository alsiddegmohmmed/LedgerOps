package com.ledgerops.risk.domain;

import com.ledgerops.risk.api.RiskDecision;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record RiskEvaluation(
        RiskEvaluationId evaluationId,
        UUID tenantId,
        UUID paymentId,
        RiskProfileId profileId,
        long profileVersion,
        long uncappedScore,
        int finalScore,
        RiskDecision decision,
        Instant evaluatedAt,
        List<EvaluatedRiskRule> ruleResults
) {

    public RiskEvaluation {
        Objects.requireNonNull(evaluationId, "Risk evaluation ID must not be null");
        Objects.requireNonNull(tenantId, "Tenant ID must not be null");
        Objects.requireNonNull(paymentId, "Payment ID must not be null");
        Objects.requireNonNull(profileId, "Risk profile ID must not be null");
        Objects.requireNonNull(decision, "Risk decision must not be null");
        Objects.requireNonNull(evaluatedAt, "Risk evaluation time must not be null");
        ruleResults = List.copyOf(
                Objects.requireNonNull(ruleResults, "Evaluated Risk rules must not be null")
        );

        if (profileVersion < 1) {
            throw new IllegalArgumentException("Risk profile version must be positive");
        }
        if (ruleResults.isEmpty()) {
            throw new IllegalArgumentException("Risk evaluation must contain eligible rule evidence");
        }

        long evidenceScore = 0;
        for (EvaluatedRiskRule result : ruleResults) {
            evidenceScore = Math.addExact(evidenceScore, result.appliedContribution());
        }
        if (uncappedScore != evidenceScore) {
            throw new IllegalArgumentException(
                    "Uncapped Risk score must equal the applied rule contributions"
            );
        }
        if (finalScore != Math.min(100L, uncappedScore)) {
            throw new IllegalArgumentException("Final Risk score must be capped at 100");
        }
    }
}
