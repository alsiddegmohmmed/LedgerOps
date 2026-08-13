package com.ledgerops.reporting.api;

import java.math.BigDecimal;
import java.util.Objects;

public record OperationalSummaryAmount(
        String currency,
        BigDecimal amount
) {

    public OperationalSummaryAmount {
        Objects.requireNonNull(currency, "Summary currency must not be null");
        Objects.requireNonNull(amount, "Summary amount must not be null");
        currency = currency.trim().toUpperCase(java.util.Locale.ROOT);
        if (currency.length() != 3 || amount.signum() < 0) {
            throw new IllegalArgumentException("Summary currency or amount is invalid");
        }
    }
}
