package com.ledgerops.administration.api;

import com.ledgerops.identity.api.AuthenticatedPrincipal;
import com.ledgerops.identity.api.AuthorizedRequestContext;

import java.util.Objects;
import java.util.UUID;

public record MerchantLifecycleCommand(
        UUID tenantId,
        UUID merchantId,
        boolean confirmation,
        String reason,
        AuthorizedRequestContext authorization,
        AuthenticatedPrincipal actor,
        UUID correlationId,
        UUID operationId
) {

    public MerchantLifecycleCommand {
        Objects.requireNonNull(tenantId, "Tenant ID must not be null");
        Objects.requireNonNull(merchantId, "Merchant ID must not be null");
        if (reason == null || reason.isBlank() || reason.trim().length() > 512) {
            throw new IllegalArgumentException(
                    "Merchant lifecycle reason must be 1 to 512 characters");
        }
        Objects.requireNonNull(authorization, "Authorization context must not be null");
        Objects.requireNonNull(actor, "Authenticated actor must not be null");
        Objects.requireNonNull(correlationId, "Correlation ID must not be null");
        Objects.requireNonNull(operationId, "Operation ID must not be null");
        reason = reason.trim();
    }
}
