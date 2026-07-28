package com.ledgerops.identity.infrastructure;

public final class InvalidJwtPrincipalException extends RuntimeException {

    public InvalidJwtPrincipalException(String message) {
        super(message);
    }

    public InvalidJwtPrincipalException(String message, Throwable cause) {
        super(message, cause);
    }
}
