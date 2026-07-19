package com.ledgerops.customer.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

interface SpringDataCustomerRepository
        extends JpaRepository<CustomerJpaEntity, UUID> {

    Optional<CustomerJpaEntity> findByTenantIdAndMerchantIdAndId(
            UUID tenantId,
            UUID merchantId,
            UUID id
    );

    Optional<CustomerJpaEntity> findByTenantIdAndMerchantIdAndCustomerReference(
            UUID tenantId,
            UUID merchantId,
            String customerReference
    );
}
