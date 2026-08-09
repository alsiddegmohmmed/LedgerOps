package com.ledgerops.identity.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

interface SpringDataInvitationGrantRepository
        extends JpaRepository<InvitationGrantJpaEntity, InvitationGrantJpaId> {

    List<InvitationGrantJpaEntity> findAllByIdInvitationId(UUID invitationId);
}
