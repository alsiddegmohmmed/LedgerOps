package com.ledgerops.administration.api;

import com.ledgerops.identity.api.AuthenticatedPrincipal;
import com.ledgerops.identity.api.AuthorizedRequestContext;

import java.util.Objects;
import java.util.UUID;

public record CredentialRevocationCommand(
        UUID tenantId,
        UUID credentialId,
        boolean confirmation,
        String reason,
        AuthorizedRequestContext authorization,
        AuthenticatedPrincipal actor
) {

    public CredentialRevocationCommand {
        Objects.requireNonNull(tenantId, "Tenant ID must not be null");
        Objects.requireNonNull(credentialId, "Credential ID must not be null");
        reason = CredentialProvisioningCommand.requireReason(reason);
        Objects.requireNonNull(authorization, "Authorization context must not be null");
        Objects.requireNonNull(actor, "Actor must not be null");
    }
}
