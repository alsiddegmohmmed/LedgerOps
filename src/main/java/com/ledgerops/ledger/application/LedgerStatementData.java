package com.ledgerops.ledger.application;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

public record LedgerStatementData(
        BigDecimal totalDebits,
        BigDecimal totalCredits,
        long totalEntries,
        List<LedgerStatementEntry> entries
) {

    public LedgerStatementData {
        Objects.requireNonNull(totalDebits, "Debit total must not be null");
        Objects.requireNonNull(totalCredits, "Credit total must not be null");
        if (totalEntries < 0) {
            throw new IllegalArgumentException("Total entry count must not be negative");
        }
        entries = List.copyOf(
                Objects.requireNonNull(entries, "Statement entries must not be null")
        );
    }
}
