package com.ledgerops.administration.application;

public final class InvalidCredentialCursorException extends RuntimeException {

    public InvalidCredentialCursorException() {
        super("The credential page cursor is invalid or incompatible with this query");
    }
}
