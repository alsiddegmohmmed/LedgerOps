package com.ledgerops.administration.membership.api;

import com.ledgerops.identity.api.AuthenticatedPrincipal;
import com.ledgerops.identity.api.AuthorizedRequestContext;
import com.ledgerops.identity.api.InvitationAdministrationCommand;
import com.ledgerops.identity.api.MembershipRoleAssignmentRequest;
import com.ledgerops.identity.domain.ScopeMode;
import com.ledgerops.identity.domain.TenantRole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Set;
import java.util.UUID;

record InvitationAdministrationHttpRequest(
        @NotBlank @Email @Size(max = 254) String intendedEmail,
        @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String tokenHash,
        @NotEmpty Set<@Valid MembershipRoleAssignmentHttpRequest> assignments,
        @AssertTrue boolean confirmation,
        @NotBlank @Size(max = 512) String reason
) {

    InvitationAdministrationCommand toCommand(
            UUID tenantId,
            UUID revokedMembershipId,
            AuthorizedRequestContext authorization,
            AuthenticatedPrincipal actor,
            UUID correlationId
    ) {
        return new InvitationAdministrationCommand(
                tenantId,
                revokedMembershipId,
                intendedEmail,
                tokenHash,
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

record MembershipRoleAssignmentHttpRequest(
        @NotNull TenantRole role,
        @NotNull ScopeMode scopeMode,
        Set<UUID> merchantIds
) {

    MembershipRoleAssignmentRequest toCommand() {
        return new MembershipRoleAssignmentRequest(
                role, scopeMode, merchantIds == null ? Set.of() : merchantIds);
    }
}
