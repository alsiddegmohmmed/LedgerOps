package com.ledgerops.identity.application;

import com.ledgerops.identity.domain.KeycloakIdentity;
import com.ledgerops.identity.domain.PrincipalType;

import java.util.Objects;

public record ValidatedPrincipal(
        PrincipalType principalType,
        KeycloakIdentity keycloakIdentity,
        String serviceClientId
) {

    public ValidatedPrincipal {
        Objects.requireNonNull(principalType, "Principal type must not be null");
        Objects.requireNonNull(keycloakIdentity, "Keycloak identity must not be null");
        if (principalType == PrincipalType.SERVICE
                && (serviceClientId == null || serviceClientId.isBlank())) {
            throw new IllegalArgumentException("Service principal requires a client ID");
        }
        if (principalType == PrincipalType.HUMAN && serviceClientId != null) {
            throw new IllegalArgumentException("Human principal must not have a client ID");
        }
    }
}
