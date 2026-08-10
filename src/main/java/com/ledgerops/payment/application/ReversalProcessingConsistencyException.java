package com.ledgerops.payment.application;

public final class ReversalProcessingConsistencyException extends RuntimeException {
    public ReversalProcessingConsistencyException(String message) {
        super(message);
    }
}
