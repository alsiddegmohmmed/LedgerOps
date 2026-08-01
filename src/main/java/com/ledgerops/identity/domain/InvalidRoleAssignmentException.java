package com.ledgerops.identity.domain;

public final class InvalidRoleAssignmentException extends IllegalArgumentException {

    public InvalidRoleAssignmentException(String message) {
        super(message);
    }
}
