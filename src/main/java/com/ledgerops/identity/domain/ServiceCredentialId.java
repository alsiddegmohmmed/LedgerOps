package com.ledgerops.identity.domain;

import java.util.Objects;
import java.util.UUID;

public record ServiceCredentialId(UUID value) {

    public ServiceCredentialId {
        Objects.requireNonNull(value, "Service credential ID must not be null");
    }

    public static ServiceCredentialId newId() {
        return new ServiceCredentialId(UUID.randomUUID());
    }

    public static ServiceCredentialId from(UUID value) {
        return new ServiceCredentialId(value);
    }
}
