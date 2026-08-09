package com.ledgerops.administration.api;

import com.ledgerops.identity.api.AuthenticatedPrincipal;
import com.ledgerops.tenancy.api.TenantReference;

import java.util.Objects;
import java.util.UUID;

public record TenantLifecycleCommand(
        TenantReference tenant,
        AuthenticatedPrincipal actor,
        UUID correlationId,
        UUID operationId
) {

    public TenantLifecycleCommand {
        Objects.requireNonNull(tenant, "Tenant reference must not be null");
        Objects.requireNonNull(actor, "Authenticated actor must not be null");
        Objects.requireNonNull(correlationId, "Correlation ID must not be null");
        Objects.requireNonNull(operationId, "Operation ID must not be null");
    }
}
