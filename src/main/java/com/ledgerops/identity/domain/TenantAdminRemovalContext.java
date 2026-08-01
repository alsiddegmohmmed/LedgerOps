package com.ledgerops.identity.domain;

public enum TenantAdminRemovalContext {
    MEMBERSHIP_CHANGE,
    PLATFORM_TENANT_SUSPENSION,
    PLATFORM_TENANT_ARCHIVAL;

    boolean permitsLastAdminRemoval(TenantMembershipStatus requestedStatus) {
        return (this == PLATFORM_TENANT_SUSPENSION
                && requestedStatus == TenantMembershipStatus.SUSPENDED)
                || (this == PLATFORM_TENANT_ARCHIVAL
                && requestedStatus == TenantMembershipStatus.REVOKED);
    }
}
