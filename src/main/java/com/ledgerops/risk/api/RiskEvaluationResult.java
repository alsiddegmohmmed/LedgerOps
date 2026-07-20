package com.ledgerops.risk.api;

import java.util.Objects;
import java.util.UUID;

public record RiskEvaluationResult(
        UUID profileId,
        long profileVersion,
        long uncappedScore,
        int finalScore,
        RiskDecision decision,
        UUID evaluationId
) {

    public RiskEvaluationResult {
        Objects.requireNonNull(profileId, "Risk profile ID must not be null");
        Objects.requireNonNull(decision, "Risk decision must not be null");
        Objects.requireNonNull(evaluationId, "Risk evaluation ID must not be null");
        if (profileVersion < 1) {
            throw new IllegalArgumentException("Risk profile version must be positive");
        }
        if (uncappedScore < 0) {
            throw new IllegalArgumentException("Uncapped Risk score must not be negative");
        }
        if (finalScore < 0 || finalScore > 100 || finalScore != Math.min(100, uncappedScore)) {
            throw new IllegalArgumentException("Final Risk score must equal min(100, uncappedScore)");
        }
    }
}
