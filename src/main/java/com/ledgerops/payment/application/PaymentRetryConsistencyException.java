package com.ledgerops.payment.application;

public final class PaymentRetryConsistencyException extends RuntimeException {
    public PaymentRetryConsistencyException(String message) {
        super(message);
    }
}
