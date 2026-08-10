package com.ledgerops.payment.domain;

import java.util.Objects;
import java.util.UUID;

public record ReversalId(UUID value) {

    public ReversalId {
        Objects.requireNonNull(value, "Reversal ID must not be null");
    }

    public static ReversalId newId() {
        return new ReversalId(UUID.randomUUID());
    }

    public static ReversalId from(UUID value) {
        return new ReversalId(value);
    }
}
