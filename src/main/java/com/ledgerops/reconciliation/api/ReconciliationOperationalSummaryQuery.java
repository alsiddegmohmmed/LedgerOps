package com.ledgerops.reconciliation.api;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Read-only Reconciliation facts exposed to rebuildable Reporting projections. */
public interface ReconciliationOperationalSummaryQuery {

    List<ReconciliationDiscrepancyOperationalSummary> findDiscrepancies(
            UUID tenantId,
            Instant fromInclusive,
            Instant toExclusive,
            Set<UUID> merchantIds
    );
}
