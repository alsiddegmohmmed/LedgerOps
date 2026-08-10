package com.ledgerops.payment.application;

import java.util.Objects;
import java.util.UUID;

public record ReversalProcessingCommand(
        UUID tenantId,
        UUID paymentId,
        UUID reversalId,
        UUID correlationId,
        UUID causationId
) {
    public ReversalProcessingCommand {
        Objects.requireNonNull(tenantId, "Tenant ID must not be null");
        Objects.requireNonNull(paymentId, "Payment ID must not be null");
        Objects.requireNonNull(reversalId, "Reversal ID must not be null");
        Objects.requireNonNull(correlationId, "Correlation ID must not be null");
        Objects.requireNonNull(causationId, "Causation ID must not be null");
    }
}
