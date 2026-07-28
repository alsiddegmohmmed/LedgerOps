package com.ledgerops.audit.domain;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class AuditRecord {

    private final AuditRecordId id;
    private final AuditActorIdentity actorIdentity;
    private final AuditPrincipalType principalType;
    private final UUID tenantId;
    private final AuditActionType actionType;
    private final AuditTargetType targetType;
    private final String targetId;
    private final String correlationId;
    private final AuditReason reason;
    private final AuditDetails details;
    private final Instant occurredAt;

    private AuditRecord(
            AuditRecordId id,
            AuditActorIdentity actorIdentity,
            AuditPrincipalType principalType,
            UUID tenantId,
            AuditActionType actionType,
            AuditTargetType targetType,
            String targetId,
            String correlationId,
            AuditReason reason,
            AuditDetails details,
            Instant occurredAt
    ) {
        this.id = Objects.requireNonNull(id, "Audit record ID must not be null");
        this.actorIdentity = Objects.requireNonNull(actorIdentity, "Actor identity must not be null");
        this.principalType = Objects.requireNonNull(principalType, "Principal type must not be null");
        this.tenantId = tenantId;
        this.actionType = Objects.requireNonNull(actionType, "Audit action type must not be null");
        if (actionType.tenantOwned() && tenantId == null) {
            throw new IllegalArgumentException("Tenant-owned audit action requires a Tenant");
        }
        this.targetType = Objects.requireNonNull(targetType, "Audit target type must not be null");
        this.targetId = requireValue(targetId, "Audit target ID");
        this.correlationId = requireValue(correlationId, "Correlation ID");
        this.reason = Objects.requireNonNull(reason, "Audit reason must not be null");
        this.details = Objects.requireNonNull(details, "Audit details must not be null");
        this.occurredAt = Objects.requireNonNull(occurredAt, "Audit occurrence time must not be null");
    }

    public static AuditRecord create(
            AuditRecordId id,
            AuditActorIdentity actorIdentity,
            AuditPrincipalType principalType,
            UUID tenantId,
            AuditActionType actionType,
            AuditTargetType targetType,
            String targetId,
            String correlationId,
            AuditReason reason,
            AuditDetails details,
            Clock clock
    ) {
        return new AuditRecord(
                id,
                actorIdentity,
                principalType,
                tenantId,
                actionType,
                targetType,
                targetId,
                correlationId,
                reason,
                details,
                Objects.requireNonNull(clock, "Clock must not be null").instant()
        );
    }

    public AuditRecordId id() { return id; }
    public AuditActorIdentity actorIdentity() { return actorIdentity; }
    public AuditPrincipalType principalType() { return principalType; }
    public UUID tenantId() { return tenantId; }
    public AuditActionType actionType() { return actionType; }
    public AuditTargetType targetType() { return targetType; }
    public String targetId() { return targetId; }
    public String correlationId() { return correlationId; }
    public AuditReason reason() { return reason; }
    public AuditDetails details() { return details; }
    public Instant occurredAt() { return occurredAt; }

    private static String requireValue(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
