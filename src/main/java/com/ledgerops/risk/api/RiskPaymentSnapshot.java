package com.ledgerops.risk.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record RiskPaymentSnapshot(
        UUID evaluationId,
        UUID profileId,
        long profileVersion,
        int finalScore,
        RiskDecision decision,
        Instant evaluatedAt
) {

    public RiskPaymentSnapshot {
        Objects.requireNonNull(evaluationId, "Risk evaluation ID must not be null");
        Objects.requireNonNull(profileId, "Risk profile ID must not be null");
        Objects.requireNonNull(decision, "Risk decision must not be null");
        Objects.requireNonNull(evaluatedAt, "Risk evaluation time must not be null");
    }
}
