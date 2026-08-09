package com.ledgerops.administration.api;

final class InvalidAdministrationRequestException extends RuntimeException {

    InvalidAdministrationRequestException(String message, Throwable cause) {
        super(message, cause);
    }
}
