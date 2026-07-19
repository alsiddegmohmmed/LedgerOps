package com.ledgerops.tenancy.api;

import java.util.Objects;
import java.util.UUID;

public record TenantReference(UUID value) {

    public TenantReference {
        Objects.requireNonNull(value, "Tenant reference must not be null");
    }

    public static TenantReference from(UUID value) {
        return new TenantReference(value);
    }
}
