package com.ledgerops.payment.application;

import java.util.UUID;

public final class PaymentProviderResultConsistencyException extends RuntimeException {

    private final UUID paymentId;

    public PaymentProviderResultConsistencyException(UUID paymentId, String message) {
        super(message);
        this.paymentId = paymentId;
    }

    public PaymentProviderResultConsistencyException(
            UUID paymentId,
            String message,
            Throwable cause
    ) {
        super(message, cause);
        this.paymentId = paymentId;
    }

    public UUID paymentId() {
        return paymentId;
    }
}
