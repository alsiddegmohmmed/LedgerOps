package com.ledgerops.tenancy.api;

import java.util.Objects;
import java.util.UUID;

public record TenantLifecycleRequest(
        TenantReference tenant,
        String actorIssuer,
        String actorSubject,
        UUID correlationId,
        UUID operationId
) {

    public TenantLifecycleRequest {
        Objects.requireNonNull(tenant, "Tenant reference must not be null");
        Objects.requireNonNull(actorIssuer, "Actor issuer must not be null");
        Objects.requireNonNull(actorSubject, "Actor subject must not be null");
        Objects.requireNonNull(correlationId, "Correlation ID must not be null");
        Objects.requireNonNull(operationId, "Operation ID must not be null");
    }
}
