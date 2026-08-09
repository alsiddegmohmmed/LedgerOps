package com.ledgerops.identity.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TenantMembershipRepository {

    TenantMembership save(TenantMembership membership);

    Optional<TenantMembership> findById(TenantMembershipId membershipId);

    List<TenantMembership> findAllByTenantId(UUID tenantId);

    Optional<TenantMembership> findActiveByApplicationUserAndTenant(
            ApplicationUserId applicationUserId,
            UUID tenantId
    );
}
