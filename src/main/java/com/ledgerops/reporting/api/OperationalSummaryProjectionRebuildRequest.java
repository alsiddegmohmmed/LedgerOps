package com.ledgerops.reporting.api;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Published facts for one complete Tenant projection generation. */
public record OperationalSummaryProjectionRebuildRequest(
        UUID tenantId,
        Instant asOf,
        long cursor,
        List<OperationalSummaryFact> facts
) {

    public OperationalSummaryProjectionRebuildRequest {
        Objects.requireNonNull(tenantId, "Tenant ID must not be null");
        Objects.requireNonNull(asOf, "Projection as-of time must not be null");
        if (cursor < 0) {
            throw new IllegalArgumentException("Projection cursor must not be negative");
        }
        Objects.requireNonNull(facts, "Projection facts must not be null");

        LinkedHashMap<FactKey, OperationalSummaryFact> unique = new LinkedHashMap<>();
        for (OperationalSummaryFact fact : facts) {
            Objects.requireNonNull(fact, "Projection fact must not be null");
            if (!tenantId.equals(fact.tenantId())) {
                throw new IllegalArgumentException(
                        "Every operational-summary fact must belong to the requested Tenant");
            }
            FactKey key = new FactKey(fact.metric(), fact.sourceId());
            OperationalSummaryFact previous = unique.putIfAbsent(key, fact);
            if (previous != null && !previous.equals(fact)) {
                throw new IllegalArgumentException(
                        "One metric/source ID cannot represent conflicting operational-summary facts");
            }
        }
        facts = List.copyOf(new ArrayList<>(unique.values()));
    }

    private record FactKey(OperationalSummaryMetricCode metric, UUID sourceId) {
    }
}
