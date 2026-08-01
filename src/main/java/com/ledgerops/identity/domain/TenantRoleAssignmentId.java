package com.ledgerops.identity.domain;

import java.util.Objects;
import java.util.UUID;

public record TenantRoleAssignmentId(UUID value) {

    public TenantRoleAssignmentId {
        Objects.requireNonNull(value, "Tenant role assignment ID must not be null");
    }

    public static TenantRoleAssignmentId newId() {
        return new TenantRoleAssignmentId(UUID.randomUUID());
    }
}
