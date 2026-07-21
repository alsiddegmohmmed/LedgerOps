package com.ledgerops.tenancy.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

import java.util.Optional;
import java.util.UUID;

interface SpringDataTenantRepository
        extends JpaRepository<TenantJpaEntity, UUID> {

    boolean existsByName(String name);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select tenant from TenantJpaEntity tenant where tenant.id = :id")
    Optional<TenantJpaEntity> findByIdForUpdate(@Param("id") UUID id);
}
