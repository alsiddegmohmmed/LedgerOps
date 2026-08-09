package com.ledgerops.identity.api;

import com.ledgerops.identity.domain.Permission;
import com.ledgerops.identity.domain.PrincipalType;
import com.ledgerops.identity.domain.ScopeMode;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record AuthorizedRequestContext(
        PrincipalType principalType,
        UUID applicationUserId,
        UUID serviceCredentialId,
        UUID tenantId,
        ScopeMode scopeMode,
        Set<UUID> merchantIds,
        Set<Permission> permissions,
        String correlationId
) {

    public AuthorizedRequestContext {
        Objects.requireNonNull(principalType, "Principal type must not be null");
        Objects.requireNonNull(scopeMode, "Scope mode must not be null");
        Objects.requireNonNull(correlationId, "Correlation ID must not be null");
        if (correlationId.isBlank()) {
            throw new IllegalArgumentException("Correlation ID must not be blank");
        }
        if (principalType == PrincipalType.HUMAN && applicationUserId == null) {
            throw new IllegalArgumentException("Human context requires an application user");
        }
        if (principalType == PrincipalType.SERVICE && serviceCredentialId == null) {
            throw new IllegalArgumentException("Service context requires a credential");
        }
        if (tenantId == null) {
            throw new IllegalArgumentException("Authorized context requires a Tenant");
        }
        merchantIds = Set.copyOf(Objects.requireNonNull(merchantIds, "Merchant IDs must not be null"));
        permissions = Set.copyOf(Objects.requireNonNull(permissions, "Permissions must not be null"));
        if (scopeMode == ScopeMode.MERCHANT_SET && merchantIds.isEmpty()) {
            throw new IllegalArgumentException("Merchant scope must not be empty");
        }
    }

    public boolean hasPermission(Permission permission) {
        return permissions.contains(Objects.requireNonNull(permission, "Permission must not be null"));
    }

    public boolean canReadTenant() {
        return hasPermission(Permission.TENANT_READ);
    }

    public boolean canConfigureTenant() {
        return hasPermission(Permission.TENANT_CONFIGURE);
    }

    public boolean isHuman() {
        return principalType == PrincipalType.HUMAN;
    }

    public boolean canCreatePayment() {
        return hasPermission(Permission.PAYMENT_CREATE);
    }

    public boolean includesMerchant(UUID merchantId) {
        return merchantIds.contains(Objects.requireNonNull(merchantId, "Merchant ID must not be null"));
    }
}
