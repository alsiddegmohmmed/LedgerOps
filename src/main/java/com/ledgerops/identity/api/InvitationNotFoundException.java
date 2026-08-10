package com.ledgerops.identity.api;

/** Public Identity API error for an unavailable invitation resource. */
public final class InvitationNotFoundException extends RuntimeException {

    public InvitationNotFoundException() {
        super("Invitation was not found or is no longer pending");
    }
}
