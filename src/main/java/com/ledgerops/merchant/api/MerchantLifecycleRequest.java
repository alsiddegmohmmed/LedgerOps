package com.ledgerops.merchant.api;

import java.util.Objects;
import java.util.UUID;

public record MerchantLifecycleRequest(
        MerchantReference merchant,
        String actorIssuer,
        String actorSubject,
        UUID correlationId,
        UUID operationId
) {

    public MerchantLifecycleRequest {
        Objects.requireNonNull(merchant, "Merchant reference must not be null");
        Objects.requireNonNull(actorIssuer, "Actor issuer must not be null");
        Objects.requireNonNull(actorSubject, "Actor subject must not be null");
        Objects.requireNonNull(correlationId, "Correlation ID must not be null");
        Objects.requireNonNull(operationId, "Operation ID must not be null");
    }
}
