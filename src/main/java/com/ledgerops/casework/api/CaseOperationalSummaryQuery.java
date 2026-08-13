package com.ledgerops.casework.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Read-only Case facts exposed to rebuildable Reporting projections. */
public interface CaseOperationalSummaryQuery {

    List<CaseOperationalSummary> findUnresolvedCasesCreated(
            UUID tenantId,
            Instant fromInclusive,
            Instant toExclusive,
            Set<UUID> merchantIds
    );

    Optional<String> findCurrentStatusBySource(
            UUID tenantId,
            String sourceType,
            UUID sourceId
    );
}
