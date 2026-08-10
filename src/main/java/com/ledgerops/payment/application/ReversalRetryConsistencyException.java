package com.ledgerops.payment.application;

public final class ReversalRetryConsistencyException extends RuntimeException {
    public ReversalRetryConsistencyException(String message) {
        super(message);
    }

    public ReversalRetryConsistencyException(String message, Throwable cause) {
        super(message, cause);
    }
}
