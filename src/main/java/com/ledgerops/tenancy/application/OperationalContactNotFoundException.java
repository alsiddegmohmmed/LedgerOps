package com.ledgerops.tenancy.application;

import com.ledgerops.tenancy.domain.TenantId;

import java.util.UUID;

public final class OperationalContactNotFoundException extends RuntimeException {

    private final TenantId tenantId;
    private final UUID contactId;

    public OperationalContactNotFoundException(TenantId tenantId, UUID contactId) {
        super("Operational contact " + contactId + " was not found for Tenant " + tenantId.value());
        this.tenantId = tenantId;
        this.contactId = contactId;
    }

    public TenantId tenantId() {
        return tenantId;
    }

    public UUID contactId() {
        return contactId;
    }
}
