package com.ledgerops.tenancy.api;

final class InvalidTenantRequestException extends RuntimeException {

    InvalidTenantRequestException(String message, Throwable cause) {
        super(message, cause);
    }
}
