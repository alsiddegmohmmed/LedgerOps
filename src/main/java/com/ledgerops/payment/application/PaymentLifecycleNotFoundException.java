package com.ledgerops.payment.application;

import com.ledgerops.payment.domain.PaymentId;

import java.util.Objects;
import java.util.UUID;

public final class PaymentLifecycleNotFoundException extends RuntimeException {

    private final UUID tenantId;
    private final PaymentId paymentId;

    public PaymentLifecycleNotFoundException(UUID tenantId, PaymentId paymentId) {
        super("Payment not found in tenant scope: " + paymentId.value());
        this.tenantId = Objects.requireNonNull(tenantId, "Tenant ID must not be null");
        this.paymentId = Objects.requireNonNull(paymentId, "Payment ID must not be null");
    }

    public UUID tenantId() {
        return tenantId;
    }

    public PaymentId paymentId() {
        return paymentId;
    }
}
