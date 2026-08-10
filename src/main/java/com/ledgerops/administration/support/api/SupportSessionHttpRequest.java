package com.ledgerops.administration.support.api;

import com.ledgerops.identity.api.AuthenticatedPrincipal;
import com.ledgerops.identity.api.SupportSessionStartCommand;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

record SupportSessionHttpRequest(
        @NotNull UUID tenantId,
        @AssertTrue(message = "confirmation must be true") boolean confirmation,
        @NotBlank(message = "reason is required")
        @Size(max = 512, message = "reason must be at most 512 characters")
        String reason
) {

    SupportSessionStartCommand toCommand(
            AuthenticatedPrincipal actor,
            UUID correlationId
    ) {
        return new SupportSessionStartCommand(
                tenantId,
                confirmation,
                reason,
                actor,
                correlationId,
                UUID.randomUUID()
        );
    }
}
