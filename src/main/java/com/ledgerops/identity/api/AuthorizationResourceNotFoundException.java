package com.ledgerops.identity.api;

public final class AuthorizationResourceNotFoundException extends RuntimeException {

    public AuthorizationResourceNotFoundException() {
        super("The requested resource is outside the authorized scope");
    }
}
