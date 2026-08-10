package com.ledgerops.audit.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record AuditSearchQuery(
        UUID tenantId,
        String actorIssuer,
        String actorSubject,
        String action,
        String entity,
        String entityId,
        Instant fromInclusive,
        Instant toExclusive,
        String result,
        String correlationId,
        int limit,
        String cursor
) {

    public static final int MAXIMUM_LIMIT = 100;

    public AuditSearchQuery {
        Objects.requireNonNull(tenantId, "Tenant ID must not be null");
        if (limit < 1 || limit > MAXIMUM_LIMIT) {
            throw new IllegalArgumentException(
                    "Audit page limit must be between 1 and " + MAXIMUM_LIMIT);
        }
        if (fromInclusive != null && toExclusive != null
                && !fromInclusive.isBefore(toExclusive)) {
            throw new IllegalArgumentException(
                    "Audit search start must be before its exclusive end");
        }
        cursor = cursor == null || cursor.isBlank() ? null : cursor.trim();
    }
}
