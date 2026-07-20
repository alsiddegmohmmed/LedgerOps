package com.ledgerops.ledger.application;

import com.ledgerops.ledger.domain.LedgerAccountId;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Objects;

public record LedgerAccountStatement(
        LedgerAccountId accountId,
        Currency currency,
        Instant fromInclusive,
        Instant toExclusive,
        BigDecimal totalDebits,
        BigDecimal totalCredits,
        long totalEntries,
        int offset,
        int limit,
        List<LedgerStatementEntry> entries
) {

    public LedgerAccountStatement {
        Objects.requireNonNull(accountId, "Ledger account ID must not be null");
        Objects.requireNonNull(currency, "Ledger account currency must not be null");
        Objects.requireNonNull(fromInclusive, "Statement start must not be null");
        Objects.requireNonNull(toExclusive, "Statement end must not be null");
        LedgerAccountBalance normalizedTotals = new LedgerAccountBalance(
                accountId,
                currency,
                totalDebits,
                totalCredits,
                toExclusive
        );
        totalDebits = normalizedTotals.totalDebits();
        totalCredits = normalizedTotals.totalCredits();
        if (totalEntries < 0) {
            throw new IllegalArgumentException("Total entry count must not be negative");
        }
        if (offset < 0 || limit < 1) {
            throw new IllegalArgumentException("Statement page bounds are invalid");
        }
        entries = List.copyOf(
                Objects.requireNonNull(entries, "Statement entries must not be null")
        );
    }
}
