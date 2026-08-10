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
        String correlationId,
        UUID supportSessionId
) {

    public AuthorizedRequestContext(
            PrincipalType principalType,
            UUID applicationUserId,
            UUID serviceCredentialId,
            UUID tenantId,
            ScopeMode scopeMode,
            Set<UUID> merchantIds,
            Set<Permission> permissions,
            String correlationId
    ) {
        this(principalType, applicationUserId, serviceCredentialId, tenantId,
                scopeMode, merchantIds, permissions, correlationId, null);
    }

    public AuthorizedRequestContext {
        Objects.requireNonNull(principalType, "Principal type must not be null");
        Objects.requireNonNull(scopeMode, "Scope mode must not be null");
        Objects.requireNonNull(correlationId, "Correlation ID must not be null");
        if (correlationId.isBlank()) {
            throw new IllegalArgumentException("Correlation ID must not be blank");
        }
        boolean supportContext = supportSessionId != null;
        if (principalType == PrincipalType.HUMAN && applicationUserId == null && !supportContext) {
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
        if (supportContext && (principalType != PrincipalType.HUMAN
                || applicationUserId != null
                || serviceCredentialId != null
                || scopeMode != ScopeMode.TENANT_WIDE
                || !permissions.equals(Set.of(Permission.SUPPORT_TENANT_READ)))) {
            throw new IllegalArgumentException(
                    "Support context must be read-only Tenant-wide human access");
        }
    }

    public boolean hasPermission(Permission permission) {
        return permissions.contains(Objects.requireNonNull(permission, "Permission must not be null"));
    }

    public boolean canReadTenant() {
        return isSupportSession() || hasPermission(Permission.TENANT_READ);
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

    public boolean canReadPayments() {
        return isSupportSession() || hasPermission(Permission.PAYMENT_READ);
    }

    public boolean canAddPaymentNote() {
        return hasPermission(Permission.PAYMENT_NOTE_ADD);
    }

    public boolean canReadLedger() {
        return isSupportSession() || hasPermission(Permission.LEDGER_READ);
    }

    public boolean canReadAudit() {
        return isSupportSession() || hasPermission(Permission.AUDIT_READ);
    }

    public boolean canManageCredentials() {
        return hasPermission(Permission.CREDENTIAL_MANAGE);
    }

    public boolean canReadMerchants() {
        return isSupportSession() || hasPermission(Permission.MERCHANT_READ);
    }

    public boolean canManageMemberships() {
        return hasPermission(Permission.TENANT_MEMBERSHIP_MANAGE);
    }

    public boolean canReadMemberships() {
        return isSupportSession() || canManageMemberships();
    }

    public boolean canManageRoles() {
        return hasPermission(Permission.TENANT_ROLE_MANAGE);
    }

    public boolean canSuspendMerchant() {
        return hasPermission(Permission.MERCHANT_SUSPEND);
    }

    public boolean isSupportSession() {
        return supportSessionId != null;
    }

    public static AuthorizedRequestContext support(
            UUID tenantId,
            UUID supportSessionId,
            String correlationId
    ) {
        return new AuthorizedRequestContext(
                PrincipalType.HUMAN,
                null,
                null,
                tenantId,
                ScopeMode.TENANT_WIDE,
                Set.of(),
                Set.of(Permission.SUPPORT_TENANT_READ),
                correlationId,
                supportSessionId
        );
    }

    public boolean isTenantWide() {
        return scopeMode == ScopeMode.TENANT_WIDE;
    }

    public boolean allowsMerchant(UUID merchantId) {
        Objects.requireNonNull(merchantId, "Merchant ID must not be null");
        return scopeMode == ScopeMode.TENANT_WIDE || merchantIds.contains(merchantId);
    }

    public boolean includesMerchant(UUID merchantId) {
        return merchantIds.contains(Objects.requireNonNull(merchantId, "Merchant ID must not be null"));
    }
}
