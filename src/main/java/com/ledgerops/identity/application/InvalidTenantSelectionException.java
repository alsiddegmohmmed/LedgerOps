package com.ledgerops.identity.application;

public final class InvalidTenantSelectionException extends RuntimeException {

    public InvalidTenantSelectionException() {
        super("Selected Tenant is not authorized");
    }
}
