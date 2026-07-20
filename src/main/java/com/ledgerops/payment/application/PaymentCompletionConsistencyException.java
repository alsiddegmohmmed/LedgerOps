package com.ledgerops.payment.application;

import com.ledgerops.payment.domain.PaymentId;

import java.util.Objects;

public final class PaymentCompletionConsistencyException extends RuntimeException {

    private final PaymentId paymentId;
    private final PaymentCompletionConsistencyError error;

    public PaymentCompletionConsistencyException(
            PaymentId paymentId,
            PaymentCompletionConsistencyError error,
            String message
    ) {
        super(message);
        this.paymentId = Objects.requireNonNull(paymentId, "Payment ID must not be null");
        this.error = Objects.requireNonNull(error, "Consistency error must not be null");
    }

    public PaymentCompletionConsistencyException(
            PaymentId paymentId,
            PaymentCompletionConsistencyError error,
            String message,
            Throwable cause
    ) {
        super(message, cause);
        this.paymentId = Objects.requireNonNull(paymentId, "Payment ID must not be null");
        this.error = Objects.requireNonNull(error, "Consistency error must not be null");
    }

    public PaymentId paymentId() {
        return paymentId;
    }

    public PaymentCompletionConsistencyError error() {
        return error;
    }
}
