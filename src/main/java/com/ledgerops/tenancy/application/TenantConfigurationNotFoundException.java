package com.ledgerops.tenancy.application;

import com.ledgerops.tenancy.domain.TenantId;

public final class TenantConfigurationNotFoundException extends RuntimeException {

    private final TenantId tenantId;

    public TenantConfigurationNotFoundException(TenantId tenantId) {
        super("No Tenant configuration exists for " + tenantId.value());
        this.tenantId = tenantId;
    }

    public TenantId tenantId() {
        return tenantId;
    }
}
