package com.ledgerops.identity.infrastructure;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

interface SpringDataInvitationGrantScopeRepository
        extends JpaRepository<InvitationGrantScopeJpaEntity, InvitationGrantScopeJpaId> {

    List<InvitationGrantScopeJpaEntity> findAllByIdInvitationId(UUID invitationId);
}
