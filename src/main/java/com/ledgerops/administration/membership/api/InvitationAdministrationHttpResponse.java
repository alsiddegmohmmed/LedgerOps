package com.ledgerops.administration.membership.api;

import com.ledgerops.identity.api.InvitationAdministrationResult;

import java.time.Instant;
import java.util.UUID;

record InvitationAdministrationHttpResponse(
        UUID tenantId,
        UUID membershipId,
        UUID invitationId,
        String membershipStatus,
        String invitationStatus,
        Instant expiresAt,
        long membershipVersion
) {

    static InvitationAdministrationHttpResponse from(InvitationAdministrationResult result) {
        return new InvitationAdministrationHttpResponse(
                result.tenantId(), result.membershipId(), result.invitationId(),
                result.membershipStatus(), result.invitationStatus(), result.expiresAt(),
                result.membershipVersion()
        );
    }
}
