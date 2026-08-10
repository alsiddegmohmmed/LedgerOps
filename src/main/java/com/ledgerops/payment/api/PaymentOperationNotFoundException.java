package com.ledgerops.payment.api;

public class PaymentOperationNotFoundException extends RuntimeException {
    public PaymentOperationNotFoundException(String message) {
        super(message);
    }
}
