package com.ledgerops.messaging.api;

public final class MessageEnvelopeDecodeException extends RuntimeException {

    public MessageEnvelopeDecodeException(String message) {
        super(message);
    }

    public MessageEnvelopeDecodeException(String message, Throwable cause) {
        super(message, cause);
    }
}
