package com.ledgerops.payment.application;

import java.util.Objects;
import java.util.UUID;

public record PaymentCompletionResult(
        VersionedPayment payment,
        UUID ledgerTransactionId,
        boolean replay
) {

    public PaymentCompletionResult {
        Objects.requireNonNull(payment, "Completed Payment must not be null");
        Objects.requireNonNull(
                ledgerTransactionId,
                "Ledger transaction ID must not be null"
        );
    }
}
