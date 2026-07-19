package com.ledgerops.payment.application;

import java.util.UUID;

public final class PaymentIdempotencyConflictException extends RuntimeException {

    private final UUID tenantId;

    public PaymentIdempotencyConflictException(UUID tenantId) {
        super(
                "An existing payment uses this tenant idempotency key "
                        + "with different request content"
        );
        this.tenantId = tenantId;
    }

    public UUID tenantId() {
        return tenantId;
    }
}
