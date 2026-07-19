package com.ledgerops.tenancy.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface SpringDataTenantRepository
        extends JpaRepository<TenantJpaEntity, UUID> {

    boolean existsByName(String name);
}
