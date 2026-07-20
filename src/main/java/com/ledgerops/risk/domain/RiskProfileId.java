package com.ledgerops.risk.domain;

import java.util.Objects;
import java.util.UUID;

public record RiskProfileId(UUID value) {

    public RiskProfileId {
        Objects.requireNonNull(value, "Risk profile ID must not be null");
    }

    public static RiskProfileId from(UUID value) {
        return new RiskProfileId(value);
    }
}
