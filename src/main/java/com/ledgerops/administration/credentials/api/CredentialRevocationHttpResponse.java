package com.ledgerops.administration.credentials.api;

import com.ledgerops.administration.api.CredentialRevocationResult;

import java.util.UUID;

record CredentialRevocationHttpResponse(
        UUID credentialId,
        UUID operationId,
        UUID tenantId,
        UUID merchantId,
        String keycloakClientId,
        String status
) {

    static CredentialRevocationHttpResponse from(CredentialRevocationResult result) {
        return new CredentialRevocationHttpResponse(
                result.credentialId(),
                result.operationId(),
                result.tenantId(),
                result.merchantId(),
                result.keycloakClientId(),
                result.status()
        );
    }
}
