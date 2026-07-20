package com.ledgerops.ledger.application;

import com.ledgerops.ledger.domain.LedgerAmount;
import com.ledgerops.ledger.domain.LedgerEntryDirection;
import com.ledgerops.ledger.domain.LedgerSourceReference;
import com.ledgerops.ledger.domain.LedgerTransactionId;

import java.time.Instant;
import java.util.Objects;

public record LedgerStatementEntry(
        LedgerTransactionId transactionId,
        int entryIndex,
        LedgerSourceReference sourceReference,
        Instant postedAt,
        LedgerEntryDirection direction,
        LedgerAmount amount
) {

    public LedgerStatementEntry {
        Objects.requireNonNull(transactionId, "Ledger transaction ID must not be null");
        if (entryIndex < 0) {
            throw new IllegalArgumentException("Ledger entry index must not be negative");
        }
        Objects.requireNonNull(sourceReference, "Ledger source reference must not be null");
        Objects.requireNonNull(postedAt, "Posted time must not be null");
        Objects.requireNonNull(direction, "Ledger entry direction must not be null");
        Objects.requireNonNull(amount, "Ledger entry amount must not be null");
    }
}
