package com.ledgerops.payment.application;

public final class InvalidPaymentCursorException extends RuntimeException {

    public InvalidPaymentCursorException() {
        super("The Payment page cursor is invalid, expired, or incompatible with this query");
    }
}
