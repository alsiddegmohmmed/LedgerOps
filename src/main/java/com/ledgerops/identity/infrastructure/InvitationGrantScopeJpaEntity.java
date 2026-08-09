package com.ledgerops.identity.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "invitation_grant_merchant_scopes", schema = "identity")
class InvitationGrantScopeJpaEntity {

    @EmbeddedId
    private InvitationGrantScopeJpaId id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    protected InvitationGrantScopeJpaEntity() {
    }

    InvitationGrantScopeJpaEntity(
            UUID invitationId,
            UUID assignmentId,
            UUID tenantId,
            UUID merchantId
    ) {
        this.id = new InvitationGrantScopeJpaId(invitationId, assignmentId, merchantId);
        this.tenantId = tenantId;
    }

    UUID invitationId() { return id.invitationId(); }
    UUID assignmentId() { return id.assignmentId(); }
    UUID tenantId() { return tenantId; }
    UUID merchantId() { return id.merchantId(); }
}
