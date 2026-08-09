package com.ledgerops.identity.domain;

public final class InvalidServiceCredentialTransitionException extends IllegalStateException {

    public InvalidServiceCredentialTransitionException(String message) {
        super(message);
    }
}
