package com.ledgerops.administration.api;

import java.util.Objects;
import java.util.UUID;

public record MerchantLifecycleResult(
        UUID tenantId,
        UUID merchantId,
        String status
) {

    public MerchantLifecycleResult {
        Objects.requireNonNull(tenantId, "Tenant ID must not be null");
        Objects.requireNonNull(merchantId, "Merchant ID must not be null");
        if (status == null || status.isBlank()) {
            throw new IllegalArgumentException("Merchant status must not be blank");
        }
    }
}
