package com.ledgerops.identity.api;

public final class AuthorizationPermissionDeniedException extends RuntimeException {

    public AuthorizationPermissionDeniedException(String permission) {
        super("Required permission is not granted: " + permission);
    }
}
