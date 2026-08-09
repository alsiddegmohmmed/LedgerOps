package com.ledgerops.identity.api;

import java.util.Objects;
import java.util.UUID;

public record InvitationRevocationResult(
        UUID tenantId,
        UUID membershipId,
        UUID invitationId,
        String membershipStatus,
        String invitationStatus,
        long membershipVersion
) {

    public InvitationRevocationResult {
        Objects.requireNonNull(tenantId, "Tenant ID must not be null");
        Objects.requireNonNull(membershipId, "Membership ID must not be null");
        Objects.requireNonNull(invitationId, "Invitation ID must not be null");
        membershipStatus = requireText(membershipStatus, "Membership status");
        invitationStatus = requireText(invitationStatus, "Invitation status");
        if (membershipVersion < 0) {
            throw new IllegalArgumentException("Membership version must not be negative");
        }
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return value;
    }
}
