package com.ledgerops.identity.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "application_users", schema = "identity")
class ApplicationUserJpaEntity {

    @Id
    private UUID id;

    @Column(nullable = false, length = 255)
    private String issuer;

    @Column(nullable = false, length = 255)
    private String subject;

    @Column(nullable = false, length = 32)
    private String status;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ApplicationUserJpaEntity() {
    }

    ApplicationUserJpaEntity(
            UUID id,
            String issuer,
            String subject,
            String status,
            Instant createdAt,
            Instant updatedAt
    ) {
        this.id = id;
        this.issuer = issuer;
        this.subject = subject;
        this.status = status;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    void updateStatus(String status, Instant updatedAt) {
        this.status = status;
        this.updatedAt = updatedAt;
    }

    UUID id() {
        return id;
    }

    String issuer() {
        return issuer;
    }

    String subject() {
        return subject;
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
