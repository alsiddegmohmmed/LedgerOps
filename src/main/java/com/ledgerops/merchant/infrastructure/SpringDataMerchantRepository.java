package com.ledgerops.merchant.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

interface SpringDataMerchantRepository
        extends JpaRepository<MerchantJpaEntity, UUID> {

    Optional<MerchantJpaEntity> findByTenantIdAndId(UUID tenantId, UUID id);

    boolean existsByTenantIdAndName(UUID tenantId, String name);
}
