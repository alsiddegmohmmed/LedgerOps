package com.ledgerops.administration.membership.api;

import com.ledgerops.identity.api.AuthenticatedPrincipal;
import com.ledgerops.identity.api.AuthorizedRequestContext;
import com.ledgerops.identity.api.InvitationRevocationCommand;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

record InvitationRevocationHttpRequest(
        @AssertTrue(message = "confirmation must be true")
        boolean confirmation,

        @NotBlank(message = "reason is required")
        @Size(max = 512, message = "reason must be at most 512 characters")
        String reason
) {

    InvitationRevocationCommand toCommand(
            UUID tenantId,
            UUID membershipId,
            AuthorizedRequestContext authorization,
            AuthenticatedPrincipal actor
    ) {
        return new InvitationRevocationCommand(
                tenantId,
                membershipId,
                confirmation,
                reason,
                authorization,
                actor
        );
    }
}
