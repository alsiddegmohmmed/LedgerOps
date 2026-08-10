package com.ledgerops.identity.api;

/** Public Identity API error for an invitation state conflict. */
public final class InvitationStateConflictException extends RuntimeException {

    public InvitationStateConflictException(String message) {
        super(message);
    }
}
