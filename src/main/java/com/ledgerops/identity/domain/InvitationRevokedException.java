package com.ledgerops.identity.domain;

public final class InvitationRevokedException extends InvalidInvitationException {

    public InvitationRevokedException() {
        super("Invitation is revoked");
    }
}
