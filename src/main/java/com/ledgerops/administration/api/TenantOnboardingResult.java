package com.ledgerops.administration.api;

import java.util.Objects;
import java.util.UUID;

public record TenantOnboardingResult(
        UUID tenantId,
        UUID merchantId,
        UUID membershipId,
        UUID invitationId
) {

    public TenantOnboardingResult {
        Objects.requireNonNull(tenantId, "Tenant ID must not be null");
        Objects.requireNonNull(merchantId, "Merchant ID must not be null");
        Objects.requireNonNull(membershipId, "Membership ID must not be null");
        Objects.requireNonNull(invitationId, "Invitation ID must not be null");
    }
}
