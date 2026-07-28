package com.ledgerops.identity.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;
import java.util.UUID;

interface SpringDataServiceCredentialRepository extends JpaRepository<ServiceCredentialJpaEntity, UUID> {

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
