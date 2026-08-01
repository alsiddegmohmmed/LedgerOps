package com.ledgerops.identity.domain;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class TenantRoleAssignment {
    private final TenantRoleAssignmentId id;
    private final UUID tenantId;
    private final TenantRole role;
    private final ScopeMode scopeMode;
    private final MerchantScope merchantScope;

    private TenantRoleAssignment(TenantRoleAssignmentId id, UUID tenantId, TenantRole role,
                                 ScopeMode scopeMode, MerchantScope merchantScope) {
        this.id = Objects.requireNonNull(id, "Role assignment ID must not be null");
        this.tenantId = Objects.requireNonNull(tenantId, "Assignment Tenant ID must not be null");
        this.role = Objects.requireNonNull(role, "Role must not be null");
        this.scopeMode = Objects.requireNonNull(scopeMode, "Scope mode must not be null");
        if (!role.permits(scopeMode)) {
            throw new InvalidRoleAssignmentException(
                    role.name() + " does not permit " + scopeMode + " scope"
            );
        }
        if (scopeMode == ScopeMode.MERCHANT_SET && merchantScope == null) {
            throw new InvalidRoleAssignmentException("Merchant scope is required");
        }
        if (scopeMode == ScopeMode.TENANT_WIDE && merchantScope != null) {
            throw new InvalidRoleAssignmentException("Tenant-wide assignment cannot have Merchant scope");
        }
        if (merchantScope != null && !tenantId.equals(merchantScope.tenantId())) {
            throw new InvalidRoleAssignmentException("Merchant scope belongs to another Tenant");
        }
        this.merchantScope = merchantScope;
    }

    public static TenantRoleAssignment tenantWide(
            TenantRoleAssignmentId id,
            UUID tenantId,
            TenantRole role
    ) {
        return new TenantRoleAssignment(id, tenantId, role, ScopeMode.TENANT_WIDE, null);
    }

    public static TenantRoleAssignment merchantScoped(
            TenantRoleAssignmentId id,
            UUID tenantId,
            TenantRole role,
            MerchantScope scope
    ) {
        return new TenantRoleAssignment(id, tenantId, role, ScopeMode.MERCHANT_SET, scope);
    }

    public TenantRoleAssignmentId id() { return id; }
    public UUID tenantId() { return tenantId; }
    public TenantRole role() { return role; }
    public ScopeMode scopeMode() { return scopeMode; }
    public MerchantScope merchantScope() { return merchantScope; }
    public Set<Permission> permissions() { return role.permissions(scopeMode); }
    public boolean coversMerchants(Set<UUID> merchants) {
        return scopeMode == ScopeMode.TENANT_WIDE
                || merchantScope.merchantIds().containsAll(merchants);
    }
}
