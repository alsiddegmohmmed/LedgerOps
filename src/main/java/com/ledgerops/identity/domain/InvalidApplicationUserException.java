package com.ledgerops.identity.domain;

public final class InvalidApplicationUserException extends IllegalArgumentException {

    public InvalidApplicationUserException(String message) {
        super(message);
    }
}
