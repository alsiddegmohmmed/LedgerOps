package com.ledgerops.administration.membership.api;

import com.ledgerops.identity.api.MembershipRoleMutationResult;

import java.util.UUID;

record MembershipRoleMutationHttpResponse(
        UUID tenantId,
        UUID membershipId,
        String membershipStatus,
        long membershipVersion
) {

    static MembershipRoleMutationHttpResponse from(MembershipRoleMutationResult result) {
        return new MembershipRoleMutationHttpResponse(
                result.tenantId(), result.membershipId(), result.membershipStatus(),
                result.membershipVersion()
        );
    }
}
