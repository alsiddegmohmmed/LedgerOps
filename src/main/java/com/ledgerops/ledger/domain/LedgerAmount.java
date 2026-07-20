package com.ledgerops.ledger.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

public record LedgerAmount(BigDecimal amount, Currency currency) {

    public LedgerAmount {
        Objects.requireNonNull(amount, "Ledger amount must not be null");
        Objects.requireNonNull(currency, "Ledger currency must not be null");
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("Ledger entry amount must be greater than zero");
        }

        int fractionDigits = currency.getDefaultFractionDigits();
        if (fractionDigits < 0) {
            throw new IllegalArgumentException(
                    "Currency does not define decimal precision: "
                            + currency.getCurrencyCode()
            );
        }

        try {
            amount = amount.setScale(fractionDigits, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(
                    "Ledger amount exceeds currency precision for "
                            + currency.getCurrencyCode(),
                    exception
            );
        }
    }

    public static LedgerAmount of(BigDecimal amount, Currency currency) {
        return new LedgerAmount(amount, currency);
    }
}
