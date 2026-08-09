package com.ledgerops.identity.infrastructure;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "tenant_role_assignments", schema = "identity")
class TenantRoleAssignmentJpaEntity {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "membership_id", nullable = false)
    private TenantMembershipJpaEntity membership;

    @Column(nullable = false, length = 32)
    private String role;

    @Column(name = "scope_mode", nullable = false, length = 16)
    private String scopeMode;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(
            name = "role_assignment_merchant_scopes",
            schema = "identity",
            joinColumns = @JoinColumn(name = "role_assignment_id")
    )
    @Column(name = "merchant_id", nullable = false)
    private Set<UUID> merchantIds = new LinkedHashSet<>();

    protected TenantRoleAssignmentJpaEntity() {
    }

    TenantRoleAssignmentJpaEntity(
            UUID id,
            TenantMembershipJpaEntity membership,
            String role,
            String scopeMode,
            Set<UUID> merchantIds
    ) {
        this.id = id;
        this.membership = membership;
        this.role = role;
        this.scopeMode = scopeMode;
        this.merchantIds = new LinkedHashSet<>(merchantIds);
    }

    UUID id() { return id; }

    String role() { return role; }
    String scopeMode() { return scopeMode; }
    Set<UUID> merchantIds() { return merchantIds; }
}
