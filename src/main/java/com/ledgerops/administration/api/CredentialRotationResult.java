package com.ledgerops.administration.api;

import java.util.Objects;
import java.util.UUID;

public record CredentialRotationResult(
        UUID previousCredentialId,
        UUID credentialId,
        UUID operationId,
        UUID tenantId,
        UUID merchantId,
        String keycloakClientId,
        String clientSecret,
        String status
) {

    public CredentialRotationResult {
        Objects.requireNonNull(previousCredentialId, "Previous credential ID must not be null");
        Objects.requireNonNull(credentialId, "Credential ID must not be null");
        Objects.requireNonNull(operationId, "Operation ID must not be null");
        Objects.requireNonNull(tenantId, "Tenant ID must not be null");
        Objects.requireNonNull(merchantId, "Merchant ID must not be null");
        keycloakClientId = CredentialProvisioningCommand.requireText(
                keycloakClientId, "Keycloak client ID");
        clientSecret = CredentialProvisioningCommand.requireText(
                clientSecret, "Client secret");
        status = CredentialProvisioningCommand.requireText(status, "Credential status");
    }
}
