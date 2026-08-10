package com.ledgerops.identity.api;

import java.util.Objects;
import java.util.UUID;

public record SupportSessionStartCommand(
        UUID tenantId,
        boolean confirmation,
        String reason,
        AuthenticatedPrincipal actor,
        UUID correlationId,
        UUID operationId
) {

    public SupportSessionStartCommand {
        Objects.requireNonNull(tenantId, "Tenant ID must not be null");
        if (reason == null || reason.isBlank() || reason.trim().length() > 512) {
            throw new IllegalArgumentException(
                    "Support session reason must be 1 to 512 characters");
        }
        Objects.requireNonNull(actor, "Authenticated actor must not be null");
        Objects.requireNonNull(correlationId, "Correlation ID must not be null");
        Objects.requireNonNull(operationId, "Operation ID must not be null");
        reason = reason.trim();
    }
}
