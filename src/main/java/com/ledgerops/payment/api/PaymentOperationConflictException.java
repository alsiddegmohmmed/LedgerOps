package com.ledgerops.payment.api;

public class PaymentOperationConflictException extends RuntimeException {
    public PaymentOperationConflictException(String message) {
        super(message);
    }
}
