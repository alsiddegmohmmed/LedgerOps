package com.ledgerops.provider.infrastructure;

class PermanentlyInvalidMessageException extends RuntimeException {
    PermanentlyInvalidMessageException(String message) {
        super(message);
    }

    PermanentlyInvalidMessageException(String message, Throwable cause) {
        super(message, cause);
    }
}
