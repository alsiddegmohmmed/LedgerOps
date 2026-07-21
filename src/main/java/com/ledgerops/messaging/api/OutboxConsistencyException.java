package com.ledgerops.messaging.api;

public final class OutboxConsistencyException extends RuntimeException {

    public OutboxConsistencyException(String message) {
        super(message);
    }
}
