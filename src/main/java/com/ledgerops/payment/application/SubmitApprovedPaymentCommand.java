package com.ledgerops.payment.application;

import com.ledgerops.payment.domain.PaymentId;

import java.util.Objects;
import java.util.UUID;

public record SubmitApprovedPaymentCommand(
        UUID tenantId,
        PaymentId paymentId,
        UUID correlationId,
        UUID causationId
) {
    public SubmitApprovedPaymentCommand {
        Objects.requireNonNull(tenantId, "Tenant ID must not be null");
        Objects.requireNonNull(paymentId, "Payment ID must not be null");
        Objects.requireNonNull(correlationId, "Correlation ID must not be null");
        Objects.requireNonNull(causationId, "Causation ID must not be null");
    }
}
