package com.ledgerops.administration.credentials.api;

import com.ledgerops.administration.api.CredentialProvisioningResult;

import java.util.UUID;

record CredentialProvisioningHttpResponse(
        UUID credentialId,
        UUID operationId,
        UUID tenantId,
        UUID merchantId,
        String keycloakClientId,
        String clientSecret,
        String status
) {

    static CredentialProvisioningHttpResponse from(CredentialProvisioningResult result) {
        return new CredentialProvisioningHttpResponse(
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
