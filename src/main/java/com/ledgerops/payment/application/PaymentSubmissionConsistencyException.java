package com.ledgerops.payment.application;

import com.ledgerops.payment.domain.PaymentId;

public final class PaymentSubmissionConsistencyException extends RuntimeException {

    private final PaymentId paymentId;

    public PaymentSubmissionConsistencyException(PaymentId paymentId, String message) {
        super(message);
        this.paymentId = paymentId;
    }

    public PaymentId paymentId() {
        return paymentId;
    }
}
