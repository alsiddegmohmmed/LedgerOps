package com.ledgerops.identity.api;

import java.time.Instant;
import java.util.UUID;

public record MembershipInvitationResponse(
        UUID invitationId,
        String intendedEmail,
        String status,
        Instant expiresAt
) {
}
