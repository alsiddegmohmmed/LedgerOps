package com.ledgerops.tenancy.application;

public final class DuplicateTenantNameException extends RuntimeException {

    private final String tenantName;

    public DuplicateTenantNameException(String tenantName) {
        super("Tenant name already exists: " + tenantName);
        this.tenantName = tenantName;
    }

    public DuplicateTenantNameException(String tenantName, Throwable cause) {
        super("Tenant name already exists: " + tenantName, cause);
        this.tenantName = tenantName;
    }

    public String tenantName() {
        return tenantName;
    }
}
