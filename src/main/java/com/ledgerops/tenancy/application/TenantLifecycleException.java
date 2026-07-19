package com.ledgerops.tenancy.application;

import com.ledgerops.tenancy.domain.TenantId;
import com.ledgerops.tenancy.domain.TenantStatus;

public final class TenantLifecycleException extends RuntimeException {

    private final TenantId tenantId;
    private final TenantStatus targetStatus;

    TenantLifecycleException(
            TenantId tenantId,
            TenantStatus targetStatus,
            IllegalStateException cause
    ) {
        super(cause.getMessage(), cause);
        this.tenantId = tenantId;
        this.targetStatus = targetStatus;
    }

    public TenantId tenantId() {
        return tenantId;
    }

    public TenantStatus targetStatus() {
        return targetStatus;
    }
}
