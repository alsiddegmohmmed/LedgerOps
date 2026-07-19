package com.ledgerops.payment.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

public record Money(BigDecimal amount, Currency currency) {

    public Money {
        Objects.requireNonNull(amount, "Money amount must not be null");
        Objects.requireNonNull(currency, "Money currency must not be null");

        if (amount.signum() < 0) {
            throw new IllegalArgumentException("Money amount must not be negative");
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
                    "Money amount exceeds currency precision for "
                            + currency.getCurrencyCode(),
                    exception
            );
        }
    }

    public static Money of(BigDecimal amount, Currency currency) {
        return new Money(amount, currency);
    }

    public Money add(Money other) {
        requireSameCurrency(other);
        return new Money(amount.add(other.amount), currency);
    }

    public Money subtract(Money other) {
        requireSameCurrency(other);
        BigDecimal result = amount.subtract(other.amount);

        if (result.signum() < 0) {
            throw new IllegalArgumentException(
                    "Money subtraction must not produce a negative amount"
            );
        }

        return new Money(result, currency);
    }

    public boolean isPositive() {
        return amount.signum() > 0;
    }

    private void requireSameCurrency(Money other) {
        Objects.requireNonNull(other, "Other money must not be null");

        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException(
                    "Currency mismatch: "
                            + currency.getCurrencyCode()
                            + " and "
                            + other.currency.getCurrencyCode()
            );
        }
    }
}
