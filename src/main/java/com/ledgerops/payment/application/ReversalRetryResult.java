package com.ledgerops.payment.application;

import com.ledgerops.payment.domain.PaymentAttempt;

import java.util.Objects;
import java.util.UUID;

public record ReversalRetryResult(
        PaymentAttempt attempt,
        UUID outboxId,
        UUID messageId,
        boolean replay
) {
    public ReversalRetryResult {
        Objects.requireNonNull(attempt, "Reversal attempt must not be null");
        Objects.requireNonNull(outboxId, "Outbox ID must not be null");
        Objects.requireNonNull(messageId, "Message ID must not be null");
    }
}
