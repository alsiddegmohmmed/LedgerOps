package com.ledgerops.identity.infrastructure;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "tenant_memberships", schema = "identity")
class TenantMembershipJpaEntity {

    @Id
    private UUID id;

    @Column(name = "application_user_id")
    private UUID applicationUserId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "is_initial", nullable = false)
    private boolean initial;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @OneToMany(mappedBy = "membership", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private Set<TenantRoleAssignmentJpaEntity> roleAssignments = new LinkedHashSet<>();

    protected TenantMembershipJpaEntity() {
    }

    TenantMembershipJpaEntity(
            UUID id,
            UUID applicationUserId,
            UUID tenantId,
            String status,
            boolean initial,
            Instant createdAt,
            Instant updatedAt,
            Set<TenantRoleAssignmentJpaEntity> roleAssignments
    ) {
        this.id = id;
        this.applicationUserId = applicationUserId;
        this.tenantId = tenantId;
        this.status = status;
        this.initial = initial;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.roleAssignments = new LinkedHashSet<>(roleAssignments);
    }

    void update(UUID applicationUserId, String status, Instant updatedAt) {
        this.applicationUserId = applicationUserId;
        this.status = status;
        this.updatedAt = updatedAt;
    }

    UUID id() { return id; }

    UUID applicationUserId() { return applicationUserId; }
    UUID tenantId() { return tenantId; }
    String status() { return status; }
    boolean initial() { return initial; }
    long version() { return version; }
    Set<TenantRoleAssignmentJpaEntity> roleAssignments() { return roleAssignments; }
}
