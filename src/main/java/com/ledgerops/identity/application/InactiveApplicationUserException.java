package com.ledgerops.identity.application;

public final class InactiveApplicationUserException extends RuntimeException {

    public InactiveApplicationUserException() {
        super("Application user is deactivated");
    }
}
