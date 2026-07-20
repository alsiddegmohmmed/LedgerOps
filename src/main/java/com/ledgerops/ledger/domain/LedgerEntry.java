package com.ledgerops.ledger.domain;

import java.util.Objects;

public record LedgerEntry(
        LedgerAccountReference account,
        LedgerEntryDirection direction,
        LedgerAmount amount
) {

    public LedgerEntry {
        Objects.requireNonNull(account, "Ledger account reference must not be null");
        Objects.requireNonNull(direction, "Ledger entry direction must not be null");
        Objects.requireNonNull(amount, "Ledger entry amount must not be null");
        if (!account.currency().equals(amount.currency())) {
            throw new IllegalArgumentException(
                    "Ledger entry currency must match its account currency"
            );
        }
    }

    public static LedgerEntry debit(
            LedgerAccountReference account,
            LedgerAmount amount
    ) {
        return new LedgerEntry(account, LedgerEntryDirection.DEBIT, amount);
    }

    public static LedgerEntry credit(
            LedgerAccountReference account,
            LedgerAmount amount
    ) {
        return new LedgerEntry(account, LedgerEntryDirection.CREDIT, amount);
    }
}
