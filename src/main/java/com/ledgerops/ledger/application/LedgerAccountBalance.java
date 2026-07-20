package com.ledgerops.ledger.application;

import com.ledgerops.ledger.domain.LedgerAccountId;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Currency;
import java.util.Objects;

public record LedgerAccountBalance(
        LedgerAccountId accountId,
        Currency currency,
        BigDecimal totalDebits,
        BigDecimal totalCredits,
        Instant asOfExclusive
) {

    public LedgerAccountBalance {
        Objects.requireNonNull(accountId, "Ledger account ID must not be null");
        Objects.requireNonNull(currency, "Ledger account currency must not be null");
        Objects.requireNonNull(asOfExclusive, "Balance boundary must not be null");
        totalDebits = normalize(totalDebits, currency, "Debit total");
        totalCredits = normalize(totalCredits, currency, "Credit total");
    }

    private static BigDecimal normalize(
            BigDecimal value,
            Currency currency,
            String fieldName
    ) {
        Objects.requireNonNull(value, fieldName + " must not be null");
        if (value.signum() < 0) {
            throw new IllegalArgumentException(fieldName + " must not be negative");
        }
        return value.setScale(
                currency.getDefaultFractionDigits(),
                RoundingMode.UNNECESSARY
        );
    }
}
