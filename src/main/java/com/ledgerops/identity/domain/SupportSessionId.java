package com.ledgerops.identity.domain;

import java.util.Objects;
import java.util.UUID;

public record SupportSessionId(UUID value) {

    public SupportSessionId {
        Objects.requireNonNull(value, "Support session ID must not be null");
    }

    public static SupportSessionId newId() {
        return new SupportSessionId(UUID.randomUUID());
    }
}
