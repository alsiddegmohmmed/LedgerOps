package com.ledgerops.reporting.api;

import java.util.Set;
import java.util.UUID;

/** Read-only internal boundary for replaying Tenant-scoped Reporting invalidations. */
public interface ReportingProjectionEventQuery {

    ReportingProjectionEventReplay replayAfter(
            UUID tenantId,
            long lastEventId,
            Set<UUID> merchantIds
    );
}
