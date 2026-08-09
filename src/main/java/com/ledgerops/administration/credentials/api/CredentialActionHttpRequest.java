package com.ledgerops.administration.credentials.api;

import com.ledgerops.administration.api.CredentialRevocationCommand;
import com.ledgerops.administration.api.CredentialRotationCommand;
import com.ledgerops.identity.api.AuthenticatedPrincipal;
import com.ledgerops.identity.api.AuthorizedRequestContext;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

record CredentialActionHttpRequest(
        @AssertTrue(message = "confirmation must be true")
        boolean confirmation,

        @NotBlank(message = "reason is required")
        @Size(max = 512, message = "reason must be at most 512 characters")
        String reason
) {

    CredentialRotationCommand toRotationCommand(
            UUID tenantId,
            UUID credentialId,
            AuthorizedRequestContext authorization,
            AuthenticatedPrincipal actor
    ) {
        return new CredentialRotationCommand(
                tenantId,
                credentialId,
                confirmation,
                reason,
                authorization,
                actor
        );
    }

    CredentialRevocationCommand toRevocationCommand(
            UUID tenantId,
            UUID credentialId,
            AuthorizedRequestContext authorization,
            AuthenticatedPrincipal actor
    ) {
        return new CredentialRevocationCommand(
                tenantId,
                credentialId,
                confirmation,
                reason,
                authorization,
                actor
        );
    }
}
