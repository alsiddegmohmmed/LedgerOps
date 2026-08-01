package com.ledgerops.identity.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class Invitation {
    public static final Duration VALIDITY = Duration.ofDays(7);

    private final InvitationId id;
    private final UUID tenantId;
    private final String intendedEmail;
    private final InvitationTokenHash tokenHash;
    private final Set<TenantRoleAssignment> proposedAssignments;
    private final Instant createdAt;
    private final Instant expiresAt;
    private final InvitationStatus status;

    private Invitation(InvitationId id, UUID tenantId, String email, InvitationTokenHash tokenHash,
                       Set<TenantRoleAssignment> assignments, Instant createdAt,
                       InvitationStatus status) {
        this.id = Objects.requireNonNull(id, "Invitation ID must not be null");
        this.tenantId = Objects.requireNonNull(tenantId, "Invitation Tenant ID must not be null");
        this.intendedEmail = normalizeEmail(email);
        this.tokenHash = Objects.requireNonNull(tokenHash, "Invitation token hash must not be null");
        this.proposedAssignments = Set.copyOf(Objects.requireNonNull(
                assignments, "Proposed assignments must not be null"));
        if (this.proposedAssignments.isEmpty()) {
            throw new IllegalArgumentException("Invitation must propose at least one role assignment");
        }
        if (this.proposedAssignments.stream().anyMatch(a -> !tenantId.equals(a.tenantId()))) {
            throw new InvalidInvitationException("Invitation assignment belongs to another Tenant");
        }
        this.createdAt = Objects.requireNonNull(createdAt, "Invitation creation time must not be null");
        this.expiresAt = createdAt.plus(VALIDITY);
        this.status = Objects.requireNonNull(status, "Invitation status must not be null");
    }

    public static Invitation create(InvitationId id, UUID tenantId, String intendedEmail,
                                    InvitationTokenHash tokenHash,
                                    Set<TenantRoleAssignment> assignments, Instant createdAt) {
        return new Invitation(id, tenantId, intendedEmail, tokenHash, assignments,
                createdAt, InvitationStatus.PENDING);
    }

    public Invitation revoke() {
        if (status == InvitationStatus.REVOKED) {
            throw new InvitationRevokedException();
        }
        if (status == InvitationStatus.CONSUMED) {
            throw new InvitationAlreadyConsumedException();
        }
        return copy(InvitationStatus.REVOKED);
    }

    public Invitation consume(InvitationAcceptance acceptance, Instant now) {
        Objects.requireNonNull(acceptance, "Invitation acceptance must not be null");
        Objects.requireNonNull(now, "Acceptance time must not be null");
        if (status == InvitationStatus.REVOKED) {
            throw new InvitationRevokedException();
        }
        if (status == InvitationStatus.CONSUMED) {
            throw new InvitationAlreadyConsumedException();
        }
        if (!now.isBefore(expiresAt)) {
            throw new InvitationExpiredException();
        }
        if (!intendedEmail.equals(acceptance.verifiedEmail())) {
            throw new InvalidInvitationException("Verified email does not match invitation");
        }
        return copy(InvitationStatus.CONSUMED);
    }

    public boolean isExpired(Instant now) {
        return !Objects.requireNonNull(now, "Current time must not be null").isBefore(expiresAt);
    }

    public InvitationId id() {
        return id;
    }

    public UUID tenantId() {
        return tenantId;
    }

    public String intendedEmail() {
        return intendedEmail;
    }

    public InvitationTokenHash tokenHash() {
        return tokenHash;
    }

    public Set<TenantRoleAssignment> proposedAssignments() {
        return proposedAssignments;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public InvitationStatus status() {
        return status;
    }

    private Invitation copy(InvitationStatus next) {
        return new Invitation(
                id,
                tenantId,
                intendedEmail,
                tokenHash,
                proposedAssignments,
                createdAt,
                next
        );
    }

    private static String normalizeEmail(String email) {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("Invitation email must not be blank");
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }
}
