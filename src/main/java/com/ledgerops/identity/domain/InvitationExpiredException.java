package com.ledgerops.identity.domain;

public final class InvitationExpiredException extends InvalidInvitationException {

    public InvitationExpiredException() {
        super("Invitation has expired");
    }
}
