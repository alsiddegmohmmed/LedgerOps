package com.ledgerops.reporting.api;

import java.time.Instant;
import java.util.UUID;

/** Published local-use-case boundary for rebuilding one Tenant Reporting projection. */
public interface OperationalSummaryProjectionRebuildUseCase {

    void rebuild(UUID tenantId, Instant fromInclusive, Instant toExclusive,
                 Instant asOf, long cursor);
}
