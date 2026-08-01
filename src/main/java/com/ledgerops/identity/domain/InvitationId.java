package com.ledgerops.identity.domain;

import java.util.Objects;
import java.util.UUID;

public record InvitationId(UUID value) {

    public InvitationId {
        Objects.requireNonNull(value, "Invitation ID must not be null");
    }

    public static InvitationId newId() {
        return new InvitationId(UUID.randomUUID());
    }
}
