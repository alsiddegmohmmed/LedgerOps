package com.ledgerops.merchant.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import java.util.Optional;
import java.util.UUID;

interface SpringDataMerchantRepository
        extends JpaRepository<MerchantJpaEntity, UUID> {

    Optional<MerchantJpaEntity> findByTenantIdAndId(UUID tenantId, UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select merchant from MerchantJpaEntity merchant
            where merchant.tenantId = :tenantId and merchant.id = :id
            """)
    Optional<MerchantJpaEntity> findByTenantIdAndIdForUpdate(
            @Param("tenantId") UUID tenantId,
            @Param("id") UUID id
    );

    boolean existsByTenantIdAndStatus(UUID tenantId, String status);

    boolean existsByTenantIdAndName(UUID tenantId, String name);
}
