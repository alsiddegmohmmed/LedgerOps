package com.ledgerops.identity.application;

public final class InvitationNotFoundException extends RuntimeException {

    public InvitationNotFoundException() {
        super("Invitation was not found or is no longer pending");
    }
}
