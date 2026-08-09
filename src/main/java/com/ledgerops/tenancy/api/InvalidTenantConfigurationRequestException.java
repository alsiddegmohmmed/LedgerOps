package com.ledgerops.tenancy.api;

public final class InvalidTenantConfigurationRequestException extends RuntimeException {

    public InvalidTenantConfigurationRequestException(String message) {
        super(message);
    }

    public InvalidTenantConfigurationRequestException(String message, Throwable cause) {
        super(message, cause);
    }
}
