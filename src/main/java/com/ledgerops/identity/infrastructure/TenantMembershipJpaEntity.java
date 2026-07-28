package com.ledgerops.identity.infrastructure;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "tenant_memberships", schema = "identity")
class TenantMembershipJpaEntity {

    @Id
    private UUID id;

    @Column(name = "application_user_id", nullable = false)
    private UUID applicationUserId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "membership", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private Set<TenantRoleAssignmentJpaEntity> roleAssignments = new LinkedHashSet<>();

    protected TenantMembershipJpaEntity() {
    }

    UUID applicationUserId() { return applicationUserId; }
    UUID tenantId() { return tenantId; }
    String status() { return status; }
    Set<TenantRoleAssignmentJpaEntity> roleAssignments() { return roleAssignments; }
}
