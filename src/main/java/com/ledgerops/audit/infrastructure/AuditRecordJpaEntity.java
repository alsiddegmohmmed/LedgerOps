package com.ledgerops.audit.infrastructure;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "audit_records", schema = "audit")
class AuditRecordJpaEntity {

    @Id
    private UUID id;

    @Column(name = "actor_issuer", nullable = false, length = 255)
    private String actorIssuer;

    @Column(name = "actor_subject", nullable = false, length = 255)
    private String actorSubject;

    @Column(name = "principal_type", nullable = false, length = 16)
    private String principalType;

    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(name = "action_type", nullable = false, length = 120)
    private String actionType;

    @Column(name = "target_type", nullable = false, length = 120)
    private String targetType;

    @Column(name = "target_id", nullable = false, length = 255)
    private String targetId;

    @Column(name = "correlation_id", nullable = false, length = 120)
    private String correlationId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String reason;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String details;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected AuditRecordJpaEntity() {
    }

    AuditRecordJpaEntity(
            UUID id,
            String actorIssuer,
            String actorSubject,
            String principalType,
            UUID tenantId,
            String actionType,
            String targetType,
            String targetId,
            String correlationId,
            String reason,
            String details,
            Instant occurredAt
    ) {
        this.id = id;
        this.actorIssuer = actorIssuer;
        this.actorSubject = actorSubject;
        this.principalType = principalType;
        this.tenantId = tenantId;
        this.actionType = actionType;
        this.targetType = targetType;
        this.targetId = targetId;
        this.correlationId = correlationId;
        this.reason = reason;
        this.details = details;
        this.occurredAt = occurredAt;
    }

    UUID id() { return id; }
    String actorIssuer() { return actorIssuer; }
    String actorSubject() { return actorSubject; }
    String principalType() { return principalType; }
    UUID tenantId() { return tenantId; }
    String actionType() { return actionType; }
    String targetType() { return targetType; }
    String targetId() { return targetId; }
    String correlationId() { return correlationId; }
    String reason() { return reason; }
    String details() { return details; }
    Instant occurredAt() { return occurredAt; }
}
