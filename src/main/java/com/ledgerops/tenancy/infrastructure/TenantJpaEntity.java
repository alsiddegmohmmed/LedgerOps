package com.ledgerops.tenancy.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "tenants", schema = "tenancy")
class TenantJpaEntity {

    @Id
    private UUID id;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(name = "default_currency", nullable = false, length = 3)
    private String defaultCurrency;

    @Column(name = "default_locale", nullable = false, length = 35)
    private String defaultLocale;

    @Column(nullable = false, length = 32)
    private String status;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected TenantJpaEntity() {
    }

    TenantJpaEntity(
            UUID id,
            String name,
            String defaultCurrency,
            String defaultLocale,
            String status,
            Instant createdAt,
            Instant updatedAt
    ) {
        this.id = id;
        this.name = name;
        this.defaultCurrency = defaultCurrency;
        this.defaultLocale = defaultLocale;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    void update(
            String name,
            String defaultCurrency,
            String defaultLocale,
            String status,
            Instant updatedAt
    ) {
        this.name = name;
        this.defaultCurrency = defaultCurrency;
        this.defaultLocale = defaultLocale;
        this.status = status;
        this.updatedAt = updatedAt;
    }

    UUID id() {
        return id;
    }

    String name() {
        return name;
    }

    String defaultCurrency() {
        return defaultCurrency;
    }

    String defaultLocale() {
        return defaultLocale;
    }

    String status() {
        return status;
    }

    long version() {
        return version;
    }

    Instant createdAt() {
        return createdAt;
    }

    Instant updatedAt() {
        return updatedAt;
    }
}
