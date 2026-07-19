package com.ledgerops.customer.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "customers", schema = "customer")
class CustomerJpaEntity {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "merchant_id", nullable = false)
    private UUID merchantId;

    @Column(name = "customer_reference", nullable = false, length = 120)
    private String customerReference;

    @Column(nullable = false, length = 32)
    private String status;

    @Version
    @Column(nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected CustomerJpaEntity() {
    }

    CustomerJpaEntity(
            UUID id,
            UUID tenantId,
            UUID merchantId,
            String customerReference,
            String status,
            Instant createdAt,
            Instant updatedAt
    ) {
        this.id = id;
        this.tenantId = tenantId;
        this.merchantId = merchantId;
        this.customerReference = customerReference;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    void update(String customerReference, String status, Instant updatedAt) {
        this.customerReference = customerReference;
        this.status = status;
        this.updatedAt = updatedAt;
    }

    UUID id() {
        return id;
    }

    UUID tenantId() {
        return tenantId;
    }

    UUID merchantId() {
        return merchantId;
    }

    String customerReference() {
        return customerReference;
    }

    String status() {
        return status;
    }
}
