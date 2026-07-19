package com.ledgerops.merchant.api;

import java.util.Objects;
import java.util.UUID;

public record MerchantReference(
        UUID tenantId,
        UUID value
) {

    public MerchantReference {
        Objects.requireNonNull(tenantId, "Tenant ID must not be null");
        Objects.requireNonNull(value, "Merchant reference must not be null");
    }

    public static MerchantReference from(
            UUID tenantId,
            UUID value
    ) {
        return new MerchantReference(tenantId, value);
    }
}
