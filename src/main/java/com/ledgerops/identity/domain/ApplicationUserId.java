package com.ledgerops.identity.domain;

import java.util.Objects;
import java.util.UUID;

public record ApplicationUserId(UUID value) {

    public ApplicationUserId {
        Objects.requireNonNull(value, "Application user ID must not be null");
    }

    public static ApplicationUserId newId() {
        return new ApplicationUserId(UUID.randomUUID());
    }
}
