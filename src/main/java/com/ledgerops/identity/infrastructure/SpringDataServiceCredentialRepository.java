package com.ledgerops.identity.infrastructure;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.List;
import java.time.Instant;
import java.util.UUID;

interface SpringDataServiceCredentialRepository extends JpaRepository<ServiceCredentialJpaEntity, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select credential from ServiceCredentialJpaEntity credential where credential.id = :id")
    Optional<ServiceCredentialJpaEntity> findByIdForUpdate(@Param("id") UUID id);

    Optional<ServiceCredentialJpaEntity> findByClientId(String clientId);

    @Query("""
            select credential from ServiceCredentialJpaEntity credential
            where credential.tenantId = :tenantId
              and (:merchantId is null or credential.merchantId = :merchantId)
              and (:status is null or credential.status = :status)
              and (
                    :positionSupplied = false
                    or credential.createdAt < :beforeCreatedAt
                    or (
                        credential.createdAt = :beforeCreatedAt
                        and credential.id < :beforeCredentialId
                    )
              )
            order by credential.createdAt desc, credential.id desc
            """)
    List<ServiceCredentialJpaEntity> findPage(
            @Param("tenantId") UUID tenantId,
            @Param("merchantId") UUID merchantId,
            @Param("status") String status,
            @Param("positionSupplied") boolean positionSupplied,
            @Param("beforeCreatedAt") Instant beforeCreatedAt,
            @Param("beforeCredentialId") UUID beforeCredentialId,
            Pageable pageable
    );

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
