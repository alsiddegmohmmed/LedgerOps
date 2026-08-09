package com.ledgerops.identity.api;

import java.util.List;
import java.util.UUID;

public record MembershipResponse(
        UUID tenantId,
        UUID membershipId,
        String status,
        long version,
        boolean initial,
        boolean identityLinked,
        List<MembershipRoleResponse> roleAssignments,
        MembershipInvitationResponse invitation
) {
}
