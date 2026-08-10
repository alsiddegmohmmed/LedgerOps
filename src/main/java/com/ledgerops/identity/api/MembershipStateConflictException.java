package com.ledgerops.identity.api;

/** Public Identity API error for an invalid membership state transition. */
public final class MembershipStateConflictException extends RuntimeException {

    public MembershipStateConflictException(String message) {
        super(message);
    }
}
