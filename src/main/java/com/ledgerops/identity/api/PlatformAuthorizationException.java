package com.ledgerops.identity.api;

public final class PlatformAuthorizationException extends RuntimeException {

    public PlatformAuthorizationException() {
        super("Platform Admin authority is required for this operation");
    }
}
