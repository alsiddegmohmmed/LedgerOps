package com.ledgerops.identity.domain;

import java.util.Optional;
import java.util.UUID;

public interface TenantMembershipRepository {

    TenantMembership save(TenantMembership membership);

    Optional<TenantMembership> findById(TenantMembershipId membershipId);

    Optional<TenantMembership> findActiveByApplicationUserAndTenant(
            ApplicationUserId applicationUserId,
            UUID tenantId
    );
}
