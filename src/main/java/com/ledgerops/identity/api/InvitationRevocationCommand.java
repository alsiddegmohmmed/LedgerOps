package com.ledgerops.identity.api;

import java.util.Objects;
import java.util.UUID;

public record InvitationRevocationCommand(
        UUID tenantId,
        UUID membershipId,
        boolean confirmation,
        String reason,
        AuthorizedRequestContext authorization,
        AuthenticatedPrincipal actor
) {

    public InvitationRevocationCommand {
        Objects.requireNonNull(tenantId, "Tenant ID must not be null");
        Objects.requireNonNull(membershipId, "Membership ID must not be null");
        reason = requireReason(reason);
        Objects.requireNonNull(authorization, "Authorization context must not be null");
        Objects.requireNonNull(actor, "Actor must not be null");
    }

    private static String requireReason(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Invitation revocation reason must not be blank");
        }
        String normalized = value.trim();
        if (normalized.length() > 512) {
            throw new IllegalArgumentException(
                    "Invitation revocation reason must be at most 512 characters");
        }
        return normalized;
    }
}
