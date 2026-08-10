package com.ledgerops.payment.application;

import com.ledgerops.payment.domain.PaymentStatus;
import com.ledgerops.payment.domain.ReversalStatus;

import java.util.Objects;
import java.util.UUID;

public record ReversalProviderResultResult(
        UUID reversalId,
        ReversalStatus reversalStatus,
        PaymentStatus paymentStatus,
        ReversalProviderResultOutcome outcome,
        UUID lifecycleMessageId,
        UUID ledgerTransactionId
) {
    public ReversalProviderResultResult {
        Objects.requireNonNull(reversalId, "Reversal ID must not be null");
        Objects.requireNonNull(reversalStatus, "Reversal status must not be null");
        Objects.requireNonNull(paymentStatus, "Payment status must not be null");
        Objects.requireNonNull(outcome, "Outcome must not be null");
    }
}
