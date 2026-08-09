package com.ledgerops.identity.infrastructure;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

interface SpringDataServiceCredentialRepository extends JpaRepository<ServiceCredentialJpaEntity, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select credential from ServiceCredentialJpaEntity credential where credential.id = :id")
    Optional<ServiceCredentialJpaEntity> findByIdForUpdate(@Param("id") UUID id);

    Optional<ServiceCredentialJpaEntity> findByClientId(String clientId);

    @Query("""
            select credential from ServiceCredentialJpaEntity credential
            where credential.applicationUserId = :applicationUserId
              and credential.clientId = :clientId
              and credential.tenantId = :tenantId
              and credential.status = 'ACTIVE'
            """)
    Optional<ServiceCredentialJpaEntity> findActive(
            UUID applicationUserId,
            String clientId,
            UUID tenantId
    );
}
