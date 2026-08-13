package com.ledgerops.reporting.api;

/** Replaces one Tenant's derived operational-summary projection atomically. */
public interface OperationalSummaryProjectionRebuildPort {

    void rebuild(OperationalSummaryProjectionRebuildRequest request);
}
