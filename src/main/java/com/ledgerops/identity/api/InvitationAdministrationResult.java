package com.ledgerops.identity.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record InvitationAdministrationResult(
        UUID tenantId,
        UUID membershipId,
        UUID invitationId,
        String membershipStatus,
        String invitationStatus,
        Instant expiresAt,
        long membershipVersion
) {

    public InvitationAdministrationResult {
        Objects.requireNonNull(tenantId, "Tenant ID must not be null");
        Objects.requireNonNull(membershipId, "Membership ID must not be null");
        Objects.requireNonNull(invitationId, "Invitation ID must not be null");
        Objects.requireNonNull(expiresAt, "Invitation expiry must not be null");
        if (membershipStatus == null || membershipStatus.isBlank()) {
            throw new IllegalArgumentException("Membership status must not be blank");
        }
        if (invitationStatus == null || invitationStatus.isBlank()) {
            throw new IllegalArgumentException("Invitation status must not be blank");
        }
        if (membershipVersion < 0) {
            throw new IllegalArgumentException("Membership version must not be negative");
        }
    }
}
