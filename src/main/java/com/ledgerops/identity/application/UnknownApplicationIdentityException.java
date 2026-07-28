package com.ledgerops.identity.application;

public final class UnknownApplicationIdentityException extends RuntimeException {

    public UnknownApplicationIdentityException() {
        super("Authenticated identity is not linked to an application user");
    }
}
