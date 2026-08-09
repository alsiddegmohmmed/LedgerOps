package com.ledgerops.identity.infrastructure;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

interface SpringDataInvitationRepository extends JpaRepository<InvitationJpaEntity, UUID> {

    Optional<InvitationJpaEntity> findByMembershipId(UUID membershipId);

    @Query("""
            select invitation from InvitationJpaEntity invitation
            where invitation.tokenHash = :tokenHash
              and invitation.status = 'PENDING'
            """)
    Optional<InvitationJpaEntity> findPendingByTokenHash(@Param("tokenHash") String tokenHash);

    @Query("""
            select invitation from InvitationJpaEntity invitation
            where invitation.tokenHash = :tokenHash
              and invitation.status = 'PENDING'
            """)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<InvitationJpaEntity> findPendingByTokenHashForUpdate(
            @Param("tokenHash") String tokenHash
    );
}
