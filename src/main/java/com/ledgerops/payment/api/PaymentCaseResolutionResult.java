package com.ledgerops.payment.api;

import com.ledgerops.payment.domain.PaymentStatus;

import java.util.Objects;
import java.util.UUID;

public record PaymentCaseResolutionResult(
        UUID tenantId,
        UUID paymentId,
        PaymentStatus previousStatus,
        PaymentStatus status,
        boolean changed
) {
    public PaymentCaseResolutionResult {
        Objects.requireNonNull(tenantId, "Tenant ID must not be null");
        Objects.requireNonNull(paymentId, "Payment ID must not be null");
        Objects.requireNonNull(previousStatus, "Previous status must not be null");
        Objects.requireNonNull(status, "Payment status must not be null");
    }
}
