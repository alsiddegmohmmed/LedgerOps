package com.ledgerops.risk.api;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Read-only Risk facts exposed to rebuildable Reporting projections. */
public interface RiskOperationalSummaryQuery {

    List<RiskReviewOperationalSummary> findReviewsCreated(
            UUID tenantId,
            Instant fromInclusive,
            Instant toExclusive,
            Set<UUID> merchantIds
    );
}
