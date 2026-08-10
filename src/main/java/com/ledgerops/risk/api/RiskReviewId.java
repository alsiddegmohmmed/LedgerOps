package com.ledgerops.risk.api;

import java.util.Objects;
import java.util.UUID;

public record RiskReviewId(UUID value) {

    public RiskReviewId {
        Objects.requireNonNull(value, "Risk review ID must not be null");
    }

    public static RiskReviewId newId() {
        return new RiskReviewId(UUID.randomUUID());
    }

    public static RiskReviewId from(UUID value) {
        return new RiskReviewId(value);
    }
}
