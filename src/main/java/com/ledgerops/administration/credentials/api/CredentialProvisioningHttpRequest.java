package com.ledgerops.administration.credentials.api;

import com.ledgerops.administration.api.CredentialProvisioningCommand;
import com.ledgerops.identity.api.AuthenticatedPrincipal;
import com.ledgerops.identity.api.AuthorizedRequestContext;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

record CredentialProvisioningHttpRequest(
        @NotNull(message = "merchantId is required")
        UUID merchantId,

        @NotBlank(message = "label is required")
        @Size(max = 255, message = "label must be at most 255 characters")
        String label,

        @AssertTrue(message = "confirmation must be true")
        boolean confirmation,

        @NotBlank(message = "reason is required")
        @Size(max = 512, message = "reason must be at most 512 characters")
        String reason
) {

    CredentialProvisioningCommand toCommand(
            UUID tenantId,
            AuthorizedRequestContext authorization,
            AuthenticatedPrincipal actor
    ) {
        return new CredentialProvisioningCommand(
                tenantId,
                merchantId,
                label,
                confirmation,
                reason,
                authorization,
                actor
        );
    }
}
