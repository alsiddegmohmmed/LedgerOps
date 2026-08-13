package com.ledgerops.reporting.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record OperationalSummaryResponse(
        UUID tenantId,
        OperationalSummaryPeriod period,
        OperationalSummaryScope scope,
        Instant asOf,
        OperationalSummaryProjection projection,
        OperationalSummaryMetrics metrics
) {

    public OperationalSummaryResponse {
        Objects.requireNonNull(tenantId, "Summary Tenant ID must not be null");
        Objects.requireNonNull(period, "Summary period must not be null");
        Objects.requireNonNull(scope, "Summary scope must not be null");
        Objects.requireNonNull(asOf, "Summary snapshot time must not be null");
        Objects.requireNonNull(projection, "Summary projection metadata must not be null");
        Objects.requireNonNull(metrics, "Summary metrics must not be null");
    }
}
