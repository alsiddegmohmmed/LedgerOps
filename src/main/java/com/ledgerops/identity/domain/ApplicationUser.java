package com.ledgerops.identity.domain;

import java.util.Objects;

public final class ApplicationUser {

    private final ApplicationUserId id;
    private final KeycloakIdentity keycloakIdentity;
    private final ApplicationUserStatus status;

    private ApplicationUser(
            ApplicationUserId id,
            KeycloakIdentity keycloakIdentity,
            ApplicationUserStatus status
    ) {
        if (id == null) {
            throw new InvalidApplicationUserException("Application user ID must not be null");
        }
        if (keycloakIdentity == null) {
            throw new InvalidApplicationUserException("Keycloak identity must not be null");
        }
        this.id = id;
        this.keycloakIdentity = keycloakIdentity;
        this.status = Objects.requireNonNull(status, "Application user status must not be null");
    }

    public static ApplicationUser create(
            ApplicationUserId id,
            KeycloakIdentity keycloakIdentity
    ) {
        return new ApplicationUser(id, keycloakIdentity, ApplicationUserStatus.ACTIVE);
    }

    public ApplicationUser deactivate() {
        if (status == ApplicationUserStatus.DEACTIVATED) {
            throw new ApplicationUserAlreadyDeactivatedException(id);
        }
        return new ApplicationUser(id, keycloakIdentity, ApplicationUserStatus.DEACTIVATED);
    }

    public ApplicationUserId id() {
        return id;
    }

    public KeycloakIdentity keycloakIdentity() {
        return keycloakIdentity;
    }

    public ApplicationUserStatus status() {
        return status;
    }
}
