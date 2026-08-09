package com.ledgerops.identity.infrastructure;

import com.ledgerops.identity.domain.InvitationStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "invitations", schema = "identity")
class InvitationJpaEntity {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "membership_id", nullable = false)
    private UUID membershipId;

    @Column(name = "intended_email", nullable = false, length = 320)
    private String intendedEmail;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(nullable = false, length = 16)
    private String status;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected InvitationJpaEntity() {
    }

    InvitationJpaEntity(
            UUID id,
            UUID tenantId,
            UUID membershipId,
            String intendedEmail,
            String tokenHash,
            InvitationStatus status,
            Instant createdAt,
            Instant expiresAt
    ) {
        this.id = id;
        this.tenantId = tenantId;
        this.membershipId = membershipId;
        this.intendedEmail = intendedEmail;
        this.tokenHash = tokenHash;
        this.status = status.name();
        this.version = 0;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.updatedAt = createdAt;
    }

    void updateLifecycle(InvitationStatus status, Instant changedAt) {
        this.status = status.name();
        this.revokedAt = status == InvitationStatus.REVOKED ? changedAt : null;
        this.consumedAt = status == InvitationStatus.CONSUMED ? changedAt : null;
        this.updatedAt = changedAt;
    }

    UUID id() { return id; }
    UUID tenantId() { return tenantId; }
    UUID membershipId() { return membershipId; }
    String intendedEmail() { return intendedEmail; }
    String tokenHash() { return tokenHash; }
    InvitationStatus status() { return InvitationStatus.valueOf(status); }
    Instant createdAt() { return createdAt; }
    Instant expiresAt() { return expiresAt; }
}
