package com.ledgerops.audit.api;

import java.time.Instant;
import java.util.UUID;

public record AuditSearchItem(
        UUID auditId,
        String actorIssuer,
        String actorSubject,
        String principalType,
        UUID tenantId,
        String action,
        String entity,
        String entityId,
        String correlationId,
        String reason,
        String details,
        Instant occurredAt
) {
}
