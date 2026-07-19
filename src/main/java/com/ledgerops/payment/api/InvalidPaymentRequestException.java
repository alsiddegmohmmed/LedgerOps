package com.ledgerops.payment.api;

final class InvalidPaymentRequestException extends RuntimeException {

    InvalidPaymentRequestException(String message, Throwable cause) {
        super(message, cause);
    }
}
