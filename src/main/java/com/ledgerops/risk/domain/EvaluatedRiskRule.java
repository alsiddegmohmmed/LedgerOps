package com.ledgerops.risk.domain;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Objects;

public record EvaluatedRiskRule(
        RiskRuleId ruleId,
        RiskRuleType ruleType,
        Currency currency,
        BigDecimal amountThreshold,
        int configuredContribution,
        boolean triggered,
        int appliedContribution
) {

    public EvaluatedRiskRule {
        Objects.requireNonNull(ruleId, "Risk rule ID must not be null");
        Objects.requireNonNull(ruleType, "Risk rule type must not be null");
        Objects.requireNonNull(currency, "Risk rule currency must not be null");
        Objects.requireNonNull(amountThreshold, "Risk amount threshold must not be null");

        if (amountThreshold.signum() <= 0) {
            throw new IllegalArgumentException("Evaluated Risk threshold must be greater than zero");
        }
        if (configuredContribution < 1 || configuredContribution > 100) {
            throw new IllegalArgumentException(
                    "Evaluated Risk contribution must be from 1 through 100"
            );
        }

        amountThreshold = amountThreshold.stripTrailingZeros();

        int expectedContribution = triggered ? configuredContribution : 0;
        if (appliedContribution != expectedContribution) {
            throw new IllegalArgumentException(
                    "Applied contribution must equal the configured contribution only when triggered"
            );
        }
    }
}
