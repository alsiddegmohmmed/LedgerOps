package com.ledgerops.tenancy.application;

import com.ledgerops.identity.api.AuthenticatedPrincipal;
import com.ledgerops.identity.api.AuthorizedRequestContext;
import com.ledgerops.tenancy.api.TenantReference;

import java.util.Objects;
import java.util.UUID;

public record OperationalContactCommand(
        TenantReference tenant,
        AuthorizedRequestContext context,
        AuthenticatedPrincipal actor,
        UUID contactId,
        String displayName,
        String email,
        String purpose,
        boolean active,
        boolean confirmation,
        String reason
) {

    public OperationalContactCommand {
        Objects.requireNonNull(tenant, "Tenant reference must not be null");
        Objects.requireNonNull(context, "Authorized context must not be null");
        Objects.requireNonNull(actor, "Authenticated actor must not be null");
        Objects.requireNonNull(contactId, "Contact ID must not be null");
        Objects.requireNonNull(displayName, "Display name must not be null");
        Objects.requireNonNull(email, "Contact email must not be null");
        Objects.requireNonNull(purpose, "Contact purpose must not be null");
        if (!confirmation) {
            throw new IllegalArgumentException(
                    "Operational contact changes require explicit confirmation");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException(
                    "Operational contact change reason must not be blank");
        }
        reason = reason.trim();
    }
}
