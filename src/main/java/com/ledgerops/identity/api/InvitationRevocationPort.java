package com.ledgerops.identity.api;

public interface InvitationRevocationPort {

    InvitationRevocationResult revoke(InvitationRevocationCommand command);
}
