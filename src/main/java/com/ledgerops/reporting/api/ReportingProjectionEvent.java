package com.ledgerops.reporting.api;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** A persisted, Tenant-scoped Reporting invalidation signal. */
public record ReportingProjectionEvent(
        long eventId,
        UUID tenantId,
        long generation,
        Set<ReportingProjectionAffected> affected,
        UUID merchantId,
        Instant occurredAt
) {

    public ReportingProjectionEvent {
        if (eventId <= 0) {
            throw new IllegalArgumentException("Reporting event ID must be positive");
        }
        Objects.requireNonNull(tenantId, "Tenant ID must not be null");
        if (generation <= 0) {
            throw new IllegalArgumentException("Reporting generation must be positive");
        }
        Objects.requireNonNull(affected, "Affected projections must not be null");
        if (affected.isEmpty()) {
            throw new IllegalArgumentException("At least one affected projection is required");
        }
        if (affected.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Affected projections must not contain null");
        }
        affected = Set.copyOf(affected);
        Objects.requireNonNull(occurredAt, "Event occurrence time must not be null");
    }

    public List<ReportingProjectionAffected> affectedInWireOrder() {
        return affected.stream().sorted(Comparator.comparing(Enum::name)).toList();
    }
}
