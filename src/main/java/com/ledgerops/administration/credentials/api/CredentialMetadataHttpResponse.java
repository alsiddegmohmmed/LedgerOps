package com.ledgerops.administration.credentials.api;

import com.ledgerops.administration.api.CredentialMetadataResult;

import java.time.Instant;
import java.util.UUID;

record CredentialMetadataHttpResponse(
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

    static CredentialMetadataHttpResponse from(CredentialMetadataResult result) {
        return new CredentialMetadataHttpResponse(
                result.credentialId(),
                result.tenantId(),
                result.merchantId(),
                result.label(),
                result.keycloakClientId(),
                result.status(),
                result.provisioningOperationId(),
                result.replacesCredentialId(),
                result.disclosureStatus(),
                result.createdAt(),
                result.updatedAt()
        );
    }
}
