package com.ledgerops.identity.domain;

public class InvalidInvitationException extends IllegalStateException {

    public InvalidInvitationException(String message) {
        super(message);
    }
}
