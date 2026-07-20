package com.ledgerops.risk.domain;

import java.util.Objects;
import java.util.UUID;

public record RiskEvaluationId(UUID value) {

    public RiskEvaluationId {
        Objects.requireNonNull(value, "Risk evaluation ID must not be null");
    }

    public static RiskEvaluationId from(UUID value) {
        return new RiskEvaluationId(value);
    }

    public static RiskEvaluationId newId() {
        return new RiskEvaluationId(UUID.randomUUID());
    }
}
