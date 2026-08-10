package com.ledgerops.identity.api;

import com.ledgerops.identity.domain.ScopeMode;
import com.ledgerops.identity.domain.TenantRole;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record MembershipRoleAssignmentRequest(
        TenantRole role,
        ScopeMode scopeMode,
        Set<UUID> merchantIds
) {

    public MembershipRoleAssignmentRequest {
        Objects.requireNonNull(role, "Role must not be null");
        Objects.requireNonNull(scopeMode, "Scope mode must not be null");
        merchantIds = Set.copyOf(Objects.requireNonNull(
                merchantIds, "Merchant IDs must not be null"));
        if (scopeMode == ScopeMode.TENANT_WIDE && !merchantIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "Tenant-wide role assignment cannot include Merchant IDs");
        }
        if (scopeMode == ScopeMode.MERCHANT_SET && merchantIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "Merchant-scoped role assignment requires Merchant IDs");
        }
    }
}
