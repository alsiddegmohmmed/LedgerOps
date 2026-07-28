package com.ledgerops.identity.application;

public final class OutOfScopeTenantResourceException extends RuntimeException {

    public OutOfScopeTenantResourceException() {
        super("The requested resource is outside the authorized Tenant scope");
    }
}
