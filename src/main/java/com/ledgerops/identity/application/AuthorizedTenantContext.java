package com.ledgerops.identity.application;

import com.ledgerops.identity.domain.Permission;
import com.ledgerops.identity.domain.PrincipalType;
import com.ledgerops.identity.domain.ScopeMode;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record AuthorizedTenantContext(
        UUID tenantId,
        ScopeMode scopeMode,
        Set<UUID> merchantIds,
        Set<Permission> permissions,
        UUID serviceCredentialId
) {

    public AuthorizedTenantContext {
        Objects.requireNonNull(tenantId, "Tenant ID must not be null");
        Objects.requireNonNull(scopeMode, "Scope mode must not be null");
        merchantIds = Set.copyOf(Objects.requireNonNull(merchantIds, "Merchant IDs must not be null"));
        permissions = Set.copyOf(Objects.requireNonNull(permissions, "Permissions must not be null"));
        if (scopeMode == ScopeMode.MERCHANT_SET && merchantIds.isEmpty()) {
            throw new IllegalArgumentException("Merchant scope must not be empty");
        }
    }

    static AuthorizedTenantContext forPrincipal(
            UUID tenantId,
            PrincipalType principalType,
            ScopeMode scopeMode,
            Set<UUID> merchantIds,
            Set<Permission> permissions,
            UUID serviceCredentialId
    ) {
        if (principalType == PrincipalType.SERVICE && serviceCredentialId == null) {
            throw new IllegalArgumentException("Service authorization requires a credential");
        }
        return new AuthorizedTenantContext(
                tenantId,
                scopeMode,
                merchantIds,
                permissions,
                serviceCredentialId
        );
    }
}
