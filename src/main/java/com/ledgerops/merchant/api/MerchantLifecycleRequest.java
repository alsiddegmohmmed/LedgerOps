package com.ledgerops.merchant.api;

import java.util.Objects;
import java.util.UUID;

public record MerchantLifecycleRequest(
        MerchantReference merchant,
        String actorIssuer,
        String actorSubject,
        UUID correlationId,
        UUID operationId,
        String reason
) {

    public MerchantLifecycleRequest(
            MerchantReference merchant,
            String actorIssuer,
            String actorSubject,
            UUID correlationId,
            UUID operationId
    ) {
        this(merchant, actorIssuer, actorSubject, correlationId, operationId,
                "Merchant lifecycle change");
    }

    public MerchantLifecycleRequest {
        Objects.requireNonNull(merchant, "Merchant reference must not be null");
        Objects.requireNonNull(actorIssuer, "Actor issuer must not be null");
        Objects.requireNonNull(actorSubject, "Actor subject must not be null");
        Objects.requireNonNull(correlationId, "Correlation ID must not be null");
        Objects.requireNonNull(operationId, "Operation ID must not be null");
        if (reason == null || reason.isBlank() || reason.trim().length() > 512) {
            throw new IllegalArgumentException(
                    "Merchant lifecycle reason must be 1 to 512 characters");
        }
        reason = reason.trim();
    }
}
