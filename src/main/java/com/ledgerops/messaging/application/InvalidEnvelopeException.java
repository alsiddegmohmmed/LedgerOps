package com.ledgerops.messaging.application;

public class InvalidEnvelopeException extends RuntimeException {
    public InvalidEnvelopeException(String message) {
        super(message);
    }

    public InvalidEnvelopeException(String message, Throwable cause) {
        super(message, cause);
    }
}
