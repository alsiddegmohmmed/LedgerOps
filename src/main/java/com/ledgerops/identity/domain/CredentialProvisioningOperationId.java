package com.ledgerops.identity.domain;

import java.util.Objects;
import java.util.UUID;

public record CredentialProvisioningOperationId(UUID value) {

    public CredentialProvisioningOperationId {
        Objects.requireNonNull(value, "Credential provisioning operation ID must not be null");
    }

    public static CredentialProvisioningOperationId newId() {
        return new CredentialProvisioningOperationId(UUID.randomUUID());
    }

    public static CredentialProvisioningOperationId from(UUID value) {
        return new CredentialProvisioningOperationId(value);
    }
}
