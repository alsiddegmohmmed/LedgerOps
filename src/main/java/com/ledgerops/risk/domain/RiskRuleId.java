package com.ledgerops.risk.domain;

import java.util.Objects;
import java.util.UUID;

public record RiskRuleId(UUID value) {

    public RiskRuleId {
        Objects.requireNonNull(value, "Risk rule ID must not be null");
    }

    public static RiskRuleId from(UUID value) {
        return new RiskRuleId(value);
    }
}
