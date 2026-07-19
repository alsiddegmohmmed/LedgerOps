package com.ledgerops.tenancy.application;

import com.ledgerops.tenancy.domain.TenantId;

public final class TenantNotFoundException extends RuntimeException {

    private final TenantId tenantId;

    public TenantNotFoundException(TenantId tenantId) {
        super("Tenant not found: " + tenantId.value());
        this.tenantId = tenantId;
    }

    public TenantId tenantId() {
        return tenantId;
    }
}
