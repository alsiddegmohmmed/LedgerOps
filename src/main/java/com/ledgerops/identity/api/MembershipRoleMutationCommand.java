package com.ledgerops.identity.api;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record MembershipRoleMutationCommand(
        UUID tenantId,
        UUID membershipId,
        Set<MembershipRoleAssignmentRequest> assignments,
        boolean confirmation,
        String reason,
        AuthorizedRequestContext authorization,
        AuthenticatedPrincipal actor,
        UUID correlationId,
        UUID operationId
) {

    public MembershipRoleMutationCommand {
        Objects.requireNonNull(tenantId, "Tenant ID must not be null");
        Objects.requireNonNull(membershipId, "Membership ID must not be null");
        assignments = Set.copyOf(Objects.requireNonNull(
                assignments, "Role assignments must not be null"));
        if (assignments.isEmpty()) {
            throw new IllegalArgumentException("At least one role assignment is required");
        }
        if (reason == null || reason.isBlank() || reason.trim().length() > 512) {
            throw new IllegalArgumentException(
                    "Membership role mutation reason must be 1 to 512 characters");
        }
        Objects.requireNonNull(authorization, "Authorization context must not be null");
        Objects.requireNonNull(actor, "Actor must not be null");
        Objects.requireNonNull(correlationId, "Correlation ID must not be null");
        Objects.requireNonNull(operationId, "Operation ID must not be null");
        reason = reason.trim();
    }
}
