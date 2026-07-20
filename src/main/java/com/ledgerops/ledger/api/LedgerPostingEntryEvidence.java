package com.ledgerops.ledger.api;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Objects;
import java.util.UUID;

public record LedgerPostingEntryEvidence(
        UUID accountId,
        String accountCode,
        String direction,
        BigDecimal amount,
        Currency currency
) {

    public LedgerPostingEntryEvidence {
        Objects.requireNonNull(accountId, "Ledger account ID must not be null");
        accountCode = requireText(accountCode, "Ledger account code");
        direction = requireText(direction, "Ledger entry direction");
        Objects.requireNonNull(amount, "Ledger entry amount must not be null");
        Objects.requireNonNull(currency, "Ledger entry currency must not be null");
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
