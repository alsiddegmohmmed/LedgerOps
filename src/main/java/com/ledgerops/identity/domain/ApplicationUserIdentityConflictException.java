package com.ledgerops.identity.domain;

public final class ApplicationUserIdentityConflictException extends RuntimeException {

    public ApplicationUserIdentityConflictException(KeycloakIdentity identity) {
        super("Keycloak identity is already linked to another application user: "
                + identity.issuer() + "/" + identity.subject());
    }
}
