package com.ledgerops.risk.api;

import java.math.BigDecimal;
import java.util.Objects;

/** The closed Release 0.3 Risk rule configuration exposed to administrators. */
public record RiskRuleConfiguration(
        String currency,
        BigDecimal amountThreshold,
        int scoreContribution,
        boolean enabled
) {

    public RiskRuleConfiguration {
        currency = Objects.requireNonNull(currency, "Risk rule currency must not be null")
                .trim().toUpperCase(java.util.Locale.ROOT);
        amountThreshold = Objects.requireNonNull(
                amountThreshold, "Risk rule amount threshold must not be null");
    }
}
