package com.ledgerops.payment.application;

import com.ledgerops.payment.domain.PaymentId;

import java.util.Objects;

public final class PaymentOptimisticConcurrencyException extends RuntimeException {

    private final PaymentId paymentId;
    private final long expectedVersion;

    public PaymentOptimisticConcurrencyException(
            PaymentId paymentId,
            long expectedVersion
    ) {
        super(
                "Payment " + paymentId.value()
                        + " changed concurrently from expected version " + expectedVersion
        );
        this.paymentId = Objects.requireNonNull(paymentId, "Payment ID must not be null");
        this.expectedVersion = expectedVersion;
    }

    public PaymentId paymentId() {
        return paymentId;
    }

    public long expectedVersion() {
        return expectedVersion;
    }
}
