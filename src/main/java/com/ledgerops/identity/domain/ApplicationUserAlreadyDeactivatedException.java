package com.ledgerops.identity.domain;

public final class ApplicationUserAlreadyDeactivatedException extends RuntimeException {

    public ApplicationUserAlreadyDeactivatedException(ApplicationUserId id) {
        super("Application user is already deactivated: " + id.value());
    }
}
