package com.ledgerops.administration.membership.api;

import com.ledgerops.identity.api.InvitationRevocationResult;

import java.util.UUID;

record InvitationRevocationHttpResponse(
        UUID tenantId,
        UUID membershipId,
        UUID invitationId,
        String membershipStatus,
        String invitationStatus,
        long membershipVersion
) {

    static InvitationRevocationHttpResponse from(InvitationRevocationResult result) {
        return new InvitationRevocationHttpResponse(
                result.tenantId(),
                result.membershipId(),
                result.invitationId(),
                result.membershipStatus(),
                result.invitationStatus(),
                result.membershipVersion()
        );
    }
}
