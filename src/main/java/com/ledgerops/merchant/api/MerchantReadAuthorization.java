package com.ledgerops.merchant.api;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record MerchantReadAuthorization(
        UUID tenantId,
        boolean tenantWide,
        Set<UUID> merchantIds
) {

    public MerchantReadAuthorization {
        Objects.requireNonNull(tenantId, "Merchant read Tenant ID must not be null");
        merchantIds = Set.copyOf(Objects.requireNonNull(
                merchantIds, "Merchant read IDs must not be null"));
        if (!tenantWide && merchantIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "Merchant-scoped read authorization must include a Merchant");
        }
    }

    public boolean allows(UUID merchantId) {
        Objects.requireNonNull(merchantId, "Merchant ID must not be null");
        return tenantWide || merchantIds.contains(merchantId);
    }
}
