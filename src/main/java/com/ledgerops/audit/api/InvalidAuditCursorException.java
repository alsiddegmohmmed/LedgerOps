package com.ledgerops.audit.api;

public final class InvalidAuditCursorException extends RuntimeException {

    public InvalidAuditCursorException() {
        super("The Audit page cursor is invalid or incompatible with this query");
    }
}
