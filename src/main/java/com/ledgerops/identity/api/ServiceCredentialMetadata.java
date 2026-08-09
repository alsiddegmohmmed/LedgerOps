package com.ledgerops.identity.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Safe, non-secret service-credential metadata exposed to another Core
 * module. The client secret is deliberately not part of this projection.
 */
public record ServiceCredentialMetadata(
        UUID credentialId,
        UUID tenantId,
        UUID merchantId,
        String label,
        String keycloakClientId,
        String status,
        UUID createdByApplicationUserId,
        UUID provisioningOperationId,
        UUID replacesCredentialId,
        String disclosureStatus,
        Instant createdAt,
        Instant updatedAt
) {

    public ServiceCredentialMetadata {
        Objects.requireNonNull(credentialId, "Credential ID must not be null");
        Objects.requireNonNull(tenantId, "Tenant ID must not be null");
        Objects.requireNonNull(merchantId, "Merchant ID must not be null");
        requireText(label, "Credential label");
        requireText(keycloakClientId, "Keycloak client ID");
        requireText(status, "Credential status");
        Objects.requireNonNull(createdByApplicationUserId,
                "Credential creator must not be null");
        Objects.requireNonNull(provisioningOperationId,
                "Provisioning operation ID must not be null");
        requireText(disclosureStatus, "Credential disclosure status");
        Objects.requireNonNull(createdAt, "Credential creation time must not be null");
        Objects.requireNonNull(updatedAt, "Credential update time must not be null");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }
}
