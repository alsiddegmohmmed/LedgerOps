package com.ledgerops.identity.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

interface SpringDataTenantMembershipRepository extends JpaRepository<TenantMembershipJpaEntity, UUID> {

    @Query("""
            select distinct membership from TenantMembershipJpaEntity membership
            left join fetch membership.roleAssignments assignment
            left join fetch assignment.merchantIds
            where membership.id = :membershipId
            """)
    Optional<TenantMembershipJpaEntity> findAggregateById(UUID membershipId);

    @Query("""
            select distinct membership from TenantMembershipJpaEntity membership
            left join fetch membership.roleAssignments assignment
            left join fetch assignment.merchantIds
            where membership.applicationUserId = :applicationUserId
              and membership.tenantId = :tenantId
              and membership.status = 'ACTIVE'
            """)
    Optional<TenantMembershipJpaEntity> findActiveByApplicationUserIdAndTenantId(
            UUID applicationUserId,
            UUID tenantId
    );
}
