package com.ledgerops.identity.api;

import java.util.List;
import java.util.UUID;

public record MembershipRoleResponse(
        UUID assignmentId,
        String role,
        String scopeMode,
        List<UUID> merchantIds
) {
}
