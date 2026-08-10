package com.ledgerops.identity.domain;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.UUID;

public final class SupportSession {

    public static final long MAX_MINUTES = 30;
    public static final long MAX_AUTH_AGE_MINUTES = 5;

    private final SupportSessionId id;
    private final UUID tenantId;
    private final String actorIssuer;
    private final String actorSubject;
    private final String reason;
    private final Instant authenticationTime;
    private final Instant startedAt;
    private final Instant expiresAt;

    private SupportSession(
            SupportSessionId id,
            UUID tenantId,
            String actorIssuer,
            String actorSubject,
            String reason,
            Instant authenticationTime,
            Instant startedAt,
            Instant expiresAt
    ) {
        this.id = Objects.requireNonNull(id, "Support session ID must not be null");
        this.tenantId = Objects.requireNonNull(tenantId, "Tenant ID must not be null");
        this.actorIssuer = requireText(actorIssuer, "Actor issuer");
        this.actorSubject = requireText(actorSubject, "Actor subject");
        this.reason = requireReason(reason);
        this.authenticationTime = Objects.requireNonNull(
                authenticationTime, "Authentication time must not be null");
        this.startedAt = Objects.requireNonNull(startedAt, "Start time must not be null");
        this.expiresAt = Objects.requireNonNull(expiresAt, "Expiry time must not be null");
        if (authenticationTime.isAfter(startedAt)) {
            throw new IllegalArgumentException("Authentication time cannot be in the future");
        }
        if (!expiresAt.equals(startedAt.plus(MAX_MINUTES, ChronoUnit.MINUTES))) {
            throw new IllegalArgumentException("Support session duration must be exactly 30 minutes");
        }
    }

    public static SupportSession start(
            UUID tenantId,
            String actorIssuer,
            String actorSubject,
            String reason,
            Instant authenticationTime,
            Instant startedAt
    ) {
        Objects.requireNonNull(startedAt, "Start time must not be null");
        if (authenticationTime == null
                || authenticationTime.plus(MAX_AUTH_AGE_MINUTES, ChronoUnit.MINUTES)
                .isBefore(startedAt)) {
            throw new IllegalArgumentException(
                    "Platform authentication must be no older than five minutes");
        }
        return new SupportSession(
                SupportSessionId.newId(),
                tenantId,
                actorIssuer,
                actorSubject,
                reason,
                authenticationTime,
                startedAt,
                startedAt.plus(MAX_MINUTES, ChronoUnit.MINUTES)
        );
    }

    public static SupportSession reconstitute(
            SupportSessionId id,
            UUID tenantId,
            String actorIssuer,
            String actorSubject,
            String reason,
            Instant authenticationTime,
            Instant startedAt,
            Instant expiresAt
    ) {
        return new SupportSession(
                id, tenantId, actorIssuer, actorSubject, reason,
                authenticationTime, startedAt, expiresAt);
    }

    public boolean isActiveAt(Instant now) {
        return Objects.requireNonNull(now, "Current time must not be null").isBefore(expiresAt);
    }

    public boolean belongsTo(String issuer, String subject) {
        return actorIssuer.equals(issuer) && actorSubject.equals(subject);
    }

    public SupportSessionId id() {
        return id;
    }

    public UUID tenantId() {
        return tenantId;
    }

    public String actorIssuer() {
        return actorIssuer;
    }

    public String actorSubject() {
        return actorSubject;
    }

    public String reason() {
        return reason;
    }

    public Instant authenticationTime() {
        return authenticationTime;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    private static String requireReason(String value) {
        String reason = requireText(value, "Support session reason");
        if (reason.length() > 512) {
            throw new IllegalArgumentException(
                    "Support session reason must be at most 512 characters");
        }
        return reason;
    }
}
