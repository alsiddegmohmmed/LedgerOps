package com.ledgerops.payment.infrastructure;

final class InvalidProviderResultMessageException extends RuntimeException {

    InvalidProviderResultMessageException(String message) {
        super(message);
    }

    InvalidProviderResultMessageException(String message, Throwable cause) {
        super(message, cause);
    }
}
