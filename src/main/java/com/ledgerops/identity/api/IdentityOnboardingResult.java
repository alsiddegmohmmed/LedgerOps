package com.ledgerops.identity.api;

import java.util.Objects;
import java.util.UUID;

public record IdentityOnboardingResult(
        UUID membershipId,
        UUID invitationId
) {

    public IdentityOnboardingResult {
        Objects.requireNonNull(membershipId, "Membership ID must not be null");
        Objects.requireNonNull(invitationId, "Invitation ID must not be null");
    }
}
