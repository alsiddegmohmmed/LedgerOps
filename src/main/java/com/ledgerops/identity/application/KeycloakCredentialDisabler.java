package com.ledgerops.identity.application;

import java.util.Objects;
import java.util.UUID;

/**
 * External identity boundary for disabling a sandbox credential client.
 *
 * <p>Disabling is deliberately separate from Core persistence. Core must
 * commit local revocation before this operation is attempted, so a Keycloak
 * outage cannot leave the credential usable locally.</p>
 */
public interface KeycloakCredentialDisabler {

    void disable(DisableRequest request);

    record DisableRequest(UUID credentialId, String keycloakClientId) {
        public DisableRequest {
            Objects.requireNonNull(credentialId, "Credential ID must not be null");
            if (keycloakClientId == null || keycloakClientId.isBlank()) {
                throw new IllegalArgumentException("Keycloak client ID must not be blank");
            }
            keycloakClientId = keycloakClientId.trim();
        }
    }
}
