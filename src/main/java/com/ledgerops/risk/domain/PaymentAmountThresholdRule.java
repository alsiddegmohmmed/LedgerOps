package com.ledgerops.risk.domain;

import com.ledgerops.risk.api.RiskConfigurationError;
import com.ledgerops.risk.api.RiskConfigurationException;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Objects;

public record PaymentAmountThresholdRule(
        RiskRuleId ruleId,
        RiskProfileId profileId,
        Currency currency,
        BigDecimal amountThreshold,
        int scoreContribution,
        boolean enabled
) {

    public PaymentAmountThresholdRule {
        Objects.requireNonNull(ruleId, "Risk rule ID must not be null");
        Objects.requireNonNull(profileId, "Risk profile ID must not be null");
        Objects.requireNonNull(currency, "Risk rule currency must not be null");
        Objects.requireNonNull(amountThreshold, "Risk amount threshold must not be null");

        if (amountThreshold.signum() <= 0) {
            throw new RiskConfigurationException(
                    RiskConfigurationError.INVALID_AMOUNT_THRESHOLD,
                    "Risk amount threshold must be greater than zero"
            );
        }
        if (scoreContribution < 1 || scoreContribution > 100) {
            throw new RiskConfigurationException(
                    RiskConfigurationError.INVALID_SCORE_CONTRIBUTION,
                    "Risk score contribution must be from 1 through 100"
            );
        }

        amountThreshold = amountThreshold.stripTrailingZeros();
    }

    public boolean isEligible(Currency paymentCurrency) {
        return enabled && currency.equals(paymentCurrency);
    }

    public EvaluatedRiskRule evaluate(BigDecimal paymentAmount) {
        Objects.requireNonNull(paymentAmount, "Payment amount must not be null");
        boolean triggered = paymentAmount.compareTo(amountThreshold) >= 0;

        return new EvaluatedRiskRule(
                ruleId,
                RiskRuleType.PAYMENT_AMOUNT_THRESHOLD,
                currency,
                amountThreshold,
                scoreContribution,
                triggered,
                triggered ? scoreContribution : 0
        );
    }
}
