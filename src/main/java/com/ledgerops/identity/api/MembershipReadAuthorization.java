package com.ledgerops.identity.api;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record MembershipReadAuthorization(
        UUID tenantId,
        boolean tenantWide,
        Set<UUID> merchantIds
) {

    public MembershipReadAuthorization {
        Objects.requireNonNull(tenantId, "Membership read Tenant ID must not be null");
        merchantIds = Set.copyOf(Objects.requireNonNull(
                merchantIds, "Membership read Merchant IDs must not be null"));
        if (!tenantWide && merchantIds.isEmpty()) {
            throw new IllegalArgumentException(
                    "Merchant-scoped membership read authorization must include a Merchant");
        }
    }
}
