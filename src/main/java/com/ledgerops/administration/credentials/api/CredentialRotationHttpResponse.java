package com.ledgerops.administration.credentials.api;

import com.ledgerops.administration.api.CredentialRotationResult;

import java.util.UUID;

record CredentialRotationHttpResponse(
        UUID previousCredentialId,
        UUID credentialId,
        UUID operationId,
        UUID tenantId,
        UUID merchantId,
        String keycloakClientId,
        String clientSecret,
        String status
) {

    static CredentialRotationHttpResponse from(CredentialRotationResult result) {
        return new CredentialRotationHttpResponse(
                result.previousCredentialId(),
                result.credentialId(),
                result.operationId(),
                result.tenantId(),
                result.merchantId(),
                result.keycloakClientId(),
                result.clientSecret(),
                result.status()
        );
    }
}
