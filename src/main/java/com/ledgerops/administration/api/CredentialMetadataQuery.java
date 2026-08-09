package com.ledgerops.administration.api;

import com.ledgerops.identity.api.AuthorizedRequestContext;

import java.util.Objects;
import java.util.UUID;

public record CredentialMetadataQuery(
        UUID tenantId,
        UUID credentialId,
        AuthorizedRequestContext authorization
) {

    public CredentialMetadataQuery {
        Objects.requireNonNull(tenantId, "Tenant ID must not be null");
        Objects.requireNonNull(credentialId, "Credential ID must not be null");
        Objects.requireNonNull(authorization, "Authorization context must not be null");
    }
}
