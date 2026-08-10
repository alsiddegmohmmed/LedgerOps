package com.ledgerops.payment.application;

public final class ReversalAlreadyExistsException extends RuntimeException {
    public ReversalAlreadyExistsException() {
        super("A full Reversal already exists for this Payment");
    }
}
