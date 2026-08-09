package com.ledgerops.identity.api;

import java.util.Objects;
import java.util.UUID;

public record InitialTenantAdminInvitationRequest(
        UUID tenantId,
        String intendedEmail,
        String tokenHash,
        UUID correlationId,
        UUID operationId
) {

    public InitialTenantAdminInvitationRequest {
        Objects.requireNonNull(tenantId, "Tenant ID must not be null");
        Objects.requireNonNull(intendedEmail, "Intended email must not be null");
        Objects.requireNonNull(tokenHash, "Invitation token hash must not be null");
        Objects.requireNonNull(correlationId, "Correlation ID must not be null");
        Objects.requireNonNull(operationId, "Operation ID must not be null");
        if (!tokenHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "Invitation token hash must be 64 lowercase hexadecimal characters");
        }
    }
}
