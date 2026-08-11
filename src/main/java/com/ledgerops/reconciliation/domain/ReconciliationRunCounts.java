package com.ledgerops.reconciliation.domain;

import java.util.Objects;

public record ReconciliationRunCounts(
        long matchedCount,
        long unmatchedCount,
        long discrepancyCount
) {

    public ReconciliationRunCounts {
        if (matchedCount < 0 || unmatchedCount < 0 || discrepancyCount < 0) {
            throw new IllegalArgumentException("Reconciliation run counts must not be negative");
        }
    }

    public boolean hasDiscrepancies() {
        return discrepancyCount > 0;
    }
}
