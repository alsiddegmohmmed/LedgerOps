package com.ledgerops.identity.application;

import com.ledgerops.identity.domain.Permission;

public final class InsufficientPermissionException extends RuntimeException {

    public InsufficientPermissionException(Permission permission) {
        super("Required permission is not granted: " + permission.value());
    }
}
