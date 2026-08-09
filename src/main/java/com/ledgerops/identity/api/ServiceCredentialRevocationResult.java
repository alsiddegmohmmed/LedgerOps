package com.ledgerops.identity.api;

import java.util.Objects;
import java.util.UUID;

/**
 * Result of a revocation whose local state and external cleanup both
 * completed. It intentionally contains no client secret.
 */
public record ServiceCredentialRevocationResult(
        UUID credentialId,
        UUID operationId,
        UUID tenantId,
        UUID merchantId,
        String keycloakClientId
) {
    public ServiceCredentialRevocationResult {
        Objects.requireNonNull(credentialId, "Credential ID must not be null");
        Objects.requireNonNull(operationId, "Operation ID must not be null");
        Objects.requireNonNull(tenantId, "Tenant ID must not be null");
        Objects.requireNonNull(merchantId, "Merchant ID must not be null");
        if (keycloakClientId == null || keycloakClientId.isBlank()) {
            throw new IllegalArgumentException("Keycloak client ID must not be blank");
        }
        keycloakClientId = keycloakClientId.trim();
    }
}
