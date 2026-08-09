package com.ledgerops.identity.application;

import java.util.Objects;
import java.util.UUID;

/**
 * Outbound boundary for Keycloak client provisioning.
 *
 * <p>Implementations must perform network calls outside a Core database
 * transaction and must reconcile by the supplied deterministic client ID and
 * durable operation ID.</p>
 */
public interface KeycloakCredentialProvisioner {

    ProvisionedClient provision(ProvisioningRequest request);

    record ProvisioningRequest(
            UUID operationId,
            String keycloakClientId,
            String label
    ) {
        public ProvisioningRequest {
            Objects.requireNonNull(operationId, "Provisioning operation ID must not be null");
            Objects.requireNonNull(keycloakClientId, "Keycloak client ID must not be null");
            Objects.requireNonNull(label, "Credential label must not be null");
            if (keycloakClientId.isBlank()) {
                throw new IllegalArgumentException("Keycloak client ID must not be blank");
            }
            if (label.isBlank()) {
                throw new IllegalArgumentException("Credential label must not be blank");
            }
        }
    }

    record ProvisionedClient(String clientSecret) {
        public ProvisionedClient {
            Objects.requireNonNull(clientSecret, "Keycloak client secret must not be null");
            if (clientSecret.isBlank()) {
                throw new IllegalArgumentException("Keycloak client secret must not be blank");
            }
        }
    }
}
