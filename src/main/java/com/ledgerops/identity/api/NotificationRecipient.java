package com.ledgerops.identity.api;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Current, already-authorized recipient information exposed to derived
 * consumers. It contains no authentication secret or source-record content.
 */
public record NotificationRecipient(
        UUID applicationUserId,
        UUID tenantId,
        boolean tenantWide,
        Set<UUID> merchantIds
) {

    public NotificationRecipient {
        Objects.requireNonNull(applicationUserId, "Application user ID must not be null");
        Objects.requireNonNull(tenantId, "Recipient Tenant ID must not be null");
        merchantIds = Set.copyOf(Objects.requireNonNull(
                merchantIds, "Recipient Merchant IDs must not be null"));
        if (tenantWide && !merchantIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "Tenant-wide recipient must not carry Merchant scope");
        }
        if (!tenantWide && merchantIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "Merchant-scoped recipient must include a Merchant");
        }
    }

    public boolean allowsMerchant(UUID merchantId) {
        Objects.requireNonNull(merchantId, "Merchant ID must not be null");
        return tenantWide || merchantIds.contains(merchantId);
    }
}
