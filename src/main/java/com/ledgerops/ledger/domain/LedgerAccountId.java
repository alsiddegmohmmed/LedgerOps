package com.ledgerops.ledger.domain;

import java.util.Objects;
import java.util.UUID;

public record LedgerAccountId(UUID value) {

    public LedgerAccountId {
        Objects.requireNonNull(value, "Ledger account ID must not be null");
    }

    public static LedgerAccountId newId() {
        return new LedgerAccountId(UUID.randomUUID());
    }

    public static LedgerAccountId from(UUID value) {
        return new LedgerAccountId(value);
    }
}
