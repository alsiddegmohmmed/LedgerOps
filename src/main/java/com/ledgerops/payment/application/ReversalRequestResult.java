package com.ledgerops.payment.application;

import com.ledgerops.payment.domain.Reversal;

import java.util.Objects;

public record ReversalRequestResult(Reversal reversal) {
    public ReversalRequestResult {
        Objects.requireNonNull(reversal, "Reversal must not be null");
    }
}
