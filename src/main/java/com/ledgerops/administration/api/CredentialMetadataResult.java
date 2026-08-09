package com.ledgerops.administration.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Non-secret credential metadata for authenticated administration reads.
 */
public record CredentialMetadataResult(
        UUID credentialId,
        UUID tenantId,
        UUID merchantId,
        String label,
        String keycloakClientId,
        String status,
        UUID provisioningOperationId,
        UUID replacesCredentialId,
        String disclosureStatus,
        Instant createdAt,
        Instant updatedAt
) {

    public CredentialMetadataResult {
        Objects.requireNonNull(credentialId, "Credential ID must not be null");
        Objects.requireNonNull(tenantId, "Tenant ID must not be null");
        Objects.requireNonNull(merchantId, "Merchant ID must not be null");
        requireText(label, "Credential label");
        requireText(keycloakClientId, "Keycloak client ID");
        requireText(status, "Credential status");
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
