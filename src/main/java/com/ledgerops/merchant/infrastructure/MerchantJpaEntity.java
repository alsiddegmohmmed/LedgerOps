package com.ledgerops.merchant.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "merchants", schema = "merchant")
class MerchantJpaEntity {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(nullable = false, length = 32)
    private String status;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected MerchantJpaEntity() {
    }

    MerchantJpaEntity(
            UUID id,
            UUID tenantId,
            String name,
            String status,
            Instant createdAt,
            Instant updatedAt
    ) {
        this.id = id;
        this.tenantId = tenantId;
        this.name = name;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    void update(String name, String status, Instant updatedAt) {
        this.name = name;
        this.status = status;
        this.updatedAt = updatedAt;
    }

    UUID id() {
        return id;
    }

    UUID tenantId() {
        return tenantId;
    }

    String name() {
        return name;
    }

    String status() {
        return status;
    }
}
