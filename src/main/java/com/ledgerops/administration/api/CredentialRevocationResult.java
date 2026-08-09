package com.ledgerops.administration.api;

import java.util.Objects;
import java.util.UUID;

public record CredentialRevocationResult(
        UUID credentialId,
        UUID operationId,
        UUID tenantId,
        UUID merchantId,
        String keycloakClientId,
        String status
) {

    public CredentialRevocationResult {
        Objects.requireNonNull(credentialId, "Credential ID must not be null");
        Objects.requireNonNull(operationId, "Operation ID must not be null");
        Objects.requireNonNull(tenantId, "Tenant ID must not be null");
        Objects.requireNonNull(merchantId, "Merchant ID must not be null");
        keycloakClientId = CredentialProvisioningCommand.requireText(
                keycloakClientId, "Keycloak client ID");
        status = CredentialProvisioningCommand.requireText(status, "Credential status");
    }
}
