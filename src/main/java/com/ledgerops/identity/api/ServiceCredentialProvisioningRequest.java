package com.ledgerops.identity.api;

import java.util.Objects;
import java.util.UUID;

public record ServiceCredentialProvisioningRequest(
        UUID tenantId,
        UUID merchantId,
        String label,
        UUID createdByApplicationUserId,
        UUID replacesCredentialId
) {

    public ServiceCredentialProvisioningRequest {
        Objects.requireNonNull(tenantId, "Tenant ID must not be null");
        Objects.requireNonNull(merchantId, "Merchant ID must not be null");
        Objects.requireNonNull(label, "Credential label must not be null");
        Objects.requireNonNull(createdByApplicationUserId,
                "Credential creator must not be null");
        if (label.isBlank()) {
            throw new IllegalArgumentException("Credential label must not be blank");
        }
    }
}
