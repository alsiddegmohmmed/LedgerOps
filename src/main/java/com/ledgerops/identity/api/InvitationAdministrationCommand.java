package com.ledgerops.identity.api;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record InvitationAdministrationCommand(
        UUID tenantId,
        UUID revokedMembershipId,
        String intendedEmail,
        String tokenHash,
        Set<MembershipRoleAssignmentRequest> assignments,
        boolean confirmation,
        String reason,
        AuthorizedRequestContext authorization,
        AuthenticatedPrincipal actor,
        UUID correlationId,
        UUID operationId
) {

    public InvitationAdministrationCommand {
        Objects.requireNonNull(tenantId, "Tenant ID must not be null");
        Objects.requireNonNull(intendedEmail, "Intended email must not be null");
        Objects.requireNonNull(tokenHash, "Invitation token hash must not be null");
        if (!tokenHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "Invitation token hash must be 64 lowercase hexadecimal characters");
        }
        assignments = Set.copyOf(Objects.requireNonNull(
                assignments, "Role assignments must not be null"));
        if (assignments.isEmpty()) {
            throw new IllegalArgumentException("At least one role assignment is required");
        }
        if (reason == null || reason.isBlank() || reason.trim().length() > 512) {
            throw new IllegalArgumentException(
                    "Invitation administration reason must be 1 to 512 characters");
        }
        Objects.requireNonNull(authorization, "Authorization context must not be null");
        Objects.requireNonNull(actor, "Actor must not be null");
        Objects.requireNonNull(correlationId, "Correlation ID must not be null");
        Objects.requireNonNull(operationId, "Operation ID must not be null");
        intendedEmail = intendedEmail.trim().toLowerCase(java.util.Locale.ROOT);
        reason = reason.trim();
    }
}
