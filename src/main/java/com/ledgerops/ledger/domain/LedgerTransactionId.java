package com.ledgerops.ledger.domain;

import java.util.Objects;
import java.util.UUID;

public record LedgerTransactionId(UUID value) {

    public LedgerTransactionId {
        Objects.requireNonNull(value, "Ledger transaction ID must not be null");
    }

    public static LedgerTransactionId newId() {
        return new LedgerTransactionId(UUID.randomUUID());
    }

    public static LedgerTransactionId from(UUID value) {
        return new LedgerTransactionId(value);
    }
}
