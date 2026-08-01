package com.ledgerops.identity.domain;

import java.util.Objects;
import java.util.UUID;

public record TenantMembershipId(UUID value) {

    public TenantMembershipId {
        Objects.requireNonNull(value, "Tenant membership ID must not be null");
    }

    public static TenantMembershipId newId() {
        return new TenantMembershipId(UUID.randomUUID());
    }
}
