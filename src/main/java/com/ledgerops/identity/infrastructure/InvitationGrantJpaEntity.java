package com.ledgerops.identity.infrastructure;

import com.ledgerops.identity.domain.ScopeMode;
import com.ledgerops.identity.domain.TenantRole;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "invitation_grants", schema = "identity")
class InvitationGrantJpaEntity {

    @EmbeddedId
    private InvitationGrantJpaId id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(nullable = false, length = 32)
    private String role;

    @Column(name = "scope_mode", nullable = false, length = 16)
    private String scopeMode;

    protected InvitationGrantJpaEntity() {
    }

    InvitationGrantJpaEntity(
            UUID invitationId,
            UUID assignmentId,
            UUID tenantId,
            TenantRole role,
            ScopeMode scopeMode
    ) {
        this.id = new InvitationGrantJpaId(invitationId, assignmentId);
        this.tenantId = tenantId;
        this.role = role.name();
        this.scopeMode = scopeMode.name();
    }

    UUID invitationId() { return id.invitationId(); }
    UUID assignmentId() { return id.assignmentId(); }
    UUID tenantId() { return tenantId; }
    TenantRole role() { return TenantRole.valueOf(role); }
    ScopeMode scopeMode() { return ScopeMode.valueOf(scopeMode); }
}
