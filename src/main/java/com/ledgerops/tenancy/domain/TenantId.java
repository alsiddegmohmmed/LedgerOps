package com.ledgerops.tenancy.domain;

import java.util.Objects;
import java.util.UUID;

public record TenantId(UUID value) {

    public TenantId {
        Objects.requireNonNull(value, "Tenant ID must not be null");
    }

    public static TenantId newId() {
        return new TenantId(UUID.randomUUID());
    }

    public static TenantId from(UUID value) {
        return new TenantId(value);
    }
}
