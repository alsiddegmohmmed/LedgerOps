package com.ledgerops.administration.membership.api;

import com.ledgerops.identity.api.AuthenticatedPrincipal;
import com.ledgerops.identity.api.AuthorizedRequestContext;
import com.ledgerops.identity.api.MembershipRoleMutationCommand;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.Set;
import java.util.UUID;

record MembershipRoleMutationHttpRequest(
        @NotEmpty Set<@Valid MembershipRoleAssignmentHttpRequest> assignments,
        @AssertTrue boolean confirmation,
        @NotBlank @Size(max = 512) String reason
) {

    MembershipRoleMutationCommand toCommand(
            UUID tenantId,
            UUID membershipId,
            AuthorizedRequestContext authorization,
            AuthenticatedPrincipal actor,
            UUID correlationId
    ) {
        return new MembershipRoleMutationCommand(
                tenantId,
                membershipId,
                assignments.stream()
                        .map(MembershipRoleAssignmentHttpRequest::toCommand)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet()),
                confirmation,
                reason,
                authorization,
                actor,
                correlationId,
                UUID.randomUUID()
        );
    }
}
