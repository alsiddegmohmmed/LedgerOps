package com.ledgerops.identity.domain;

import java.util.Set;

public enum PlatformRole {
    PLATFORM_ADMIN(Set.of(
            Permission.PLATFORM_TENANT_CREATE,
            Permission.PLATFORM_TENANT_ACTIVATE,
            Permission.PLATFORM_TENANT_SUSPEND,
            Permission.PLATFORM_TENANT_REACTIVATE,
            Permission.PLATFORM_TENANT_ARCHIVE,
            Permission.PLATFORM_PROVIDER_SCENARIO_MANAGE,
            Permission.PLATFORM_HEALTH_READ,
            Permission.PLATFORM_AUDIT_READ,
            Permission.SUPPORT_TENANT_READ));

    private final Set<Permission> permissions;

    PlatformRole(Set<Permission> permissions) {
        this.permissions = Set.copyOf(permissions);
    }

    public Set<Permission> permissions() {
        return permissions;
    }
}
