package com.ledgerops.identity.domain;

public final class InvalidMembershipTransitionException extends IllegalStateException {

    public InvalidMembershipTransitionException(String message) {
        super(message);
    }
}
