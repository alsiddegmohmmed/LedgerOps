package com.ledgerops.administration.api;

import com.ledgerops.tenancy.api.TenantReference;

import java.util.Objects;

public record TenantLifecycleResult(
        TenantReference tenant,
        String status
) {

    public TenantLifecycleResult {
        Objects.requireNonNull(tenant, "Tenant reference must not be null");
        if (status == null || status.isBlank()) {
            throw new IllegalArgumentException("Tenant status must not be blank");
        }
    }
}
