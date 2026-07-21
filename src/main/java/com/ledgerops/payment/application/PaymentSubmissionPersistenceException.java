package com.ledgerops.payment.application;

import com.ledgerops.payment.domain.PaymentId;

public final class PaymentSubmissionPersistenceException extends RuntimeException {

    private final PaymentId paymentId;

    public PaymentSubmissionPersistenceException(
            PaymentId paymentId,
            String message,
            Throwable cause
    ) {
        super(message, cause);
        this.paymentId = paymentId;
    }

    public PaymentId paymentId() {
        return paymentId;
    }
}
