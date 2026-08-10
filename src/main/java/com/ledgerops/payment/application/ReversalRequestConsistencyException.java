package com.ledgerops.payment.application;

public final class ReversalRequestConsistencyException extends RuntimeException {
    public ReversalRequestConsistencyException(String message) {
        super(message);
    }
}
