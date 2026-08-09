package com.ledgerops.tenancy.api;

import com.ledgerops.identity.api.AuthenticatedPrincipal;
import com.ledgerops.identity.api.AuthorizedRequestContext;
import com.ledgerops.tenancy.application.OperationalContactCommand;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

record OperationalContactRequest(
        @NotBlank @Size(max = 160) String displayName,
        @NotBlank @Size(max = 320) String email,
        @NotBlank @Size(max = 120) String purpose,
        @NotNull Boolean active,
        @AssertTrue(message = "confirmation must be true") boolean confirmation,
        @NotBlank @Size(max = 512) String reason
) {

    OperationalContactCommand toCommand(
            TenantReference tenant,
            AuthorizedRequestContext context,
            AuthenticatedPrincipal actor,
            UUID contactId
    ) {
        return new OperationalContactCommand(
                tenant,
                context,
                actor,
                contactId,
                displayName,
                email,
                purpose,
                active,
                confirmation,
                reason
        );
    }
}
