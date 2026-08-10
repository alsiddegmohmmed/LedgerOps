package com.ledgerops.audit.application;

import com.ledgerops.audit.api.AuditSearchQuery;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface AuditSearchStore {

    Batch findBatch(AuditSearchQuery query, Instant cursorOccurredAt, UUID cursorAuditId, int limit);

    record Batch(List<Row> rows, boolean hasMore) {
        public Batch {
            rows = List.copyOf(rows);
        }
    }

    record Row(
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
}
