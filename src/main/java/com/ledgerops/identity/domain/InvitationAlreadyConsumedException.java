package com.ledgerops.identity.domain;

public final class InvitationAlreadyConsumedException extends InvalidInvitationException {

    public InvitationAlreadyConsumedException() {
        super("Invitation was already consumed");
    }
}
