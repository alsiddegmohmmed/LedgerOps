package com.ledgerops.identity.api;

import java.util.Objects;
import java.util.UUID;

public record MembershipRoleMutationResult(
        UUID tenantId,
        UUID membershipId,
        String membershipStatus,
        long membershipVersion
) {

    public MembershipRoleMutationResult {
        Objects.requireNonNull(tenantId, "Tenant ID must not be null");
        Objects.requireNonNull(membershipId, "Membership ID must not be null");
        if (membershipStatus == null || membershipStatus.isBlank()) {
            throw new IllegalArgumentException("Membership status must not be blank");
        }
        if (membershipVersion < 0) {
            throw new IllegalArgumentException("Membership version must not be negative");
        }
    }
}
