package com.ledgerops.identity.api;

import java.util.Objects;
import java.util.UUID;

/**
 * Successful one-time credential disclosure.
 *
 * <p>Callers must treat {@code clientSecret} as ephemeral response data. Core
 * does not store it and cannot redisplay it after this result is returned.</p>
 */
public record ServiceCredentialProvisioningResult(
        UUID credentialId,
        UUID operationId,
        UUID tenantId,
        UUID merchantId,
        String keycloakClientId,
        String clientSecret
) {

    public ServiceCredentialProvisioningResult {
        Objects.requireNonNull(credentialId, "Credential ID must not be null");
        Objects.requireNonNull(operationId, "Provisioning operation ID must not be null");
        Objects.requireNonNull(tenantId, "Tenant ID must not be null");
        Objects.requireNonNull(merchantId, "Merchant ID must not be null");
        Objects.requireNonNull(keycloakClientId, "Keycloak client ID must not be null");
        Objects.requireNonNull(clientSecret, "Client secret must not be null");
        if (clientSecret.isBlank()) {
            throw new IllegalArgumentException("Client secret must not be blank");
        }
    }
}
