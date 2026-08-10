package com.ledgerops.payment.application;

import com.ledgerops.payment.domain.PaymentAttempt;
import com.ledgerops.payment.domain.Reversal;

import java.util.Objects;
import java.util.UUID;

public record ReversalProcessingResult(
        Reversal reversal,
        PaymentAttempt attempt,
        UUID outboxId,
        UUID messageId,
        boolean replay
) {
    public ReversalProcessingResult {
        Objects.requireNonNull(reversal, "Reversal must not be null");
        Objects.requireNonNull(attempt, "Reversal attempt must not be null");
        Objects.requireNonNull(outboxId, "Outbox ID must not be null");
        Objects.requireNonNull(messageId, "Message ID must not be null");
    }
}
