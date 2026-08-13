package com.ledgerops.reconciliation.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Minimal discrepancy fact needed for the operational-summary metric. */
public record ReconciliationDiscrepancyOperationalSummary(
        UUID resultId,
        UUID tenantId,
        String subjectType,
        UUID subjectId,
        UUID merchantId,
        Instant detectedAt,
        boolean currentReconciliationRun
) {

    public ReconciliationDiscrepancyOperationalSummary {
        Objects.requireNonNull(resultId, "Reconciliation result ID must not be null");
        Objects.requireNonNull(tenantId, "Reconciliation Tenant ID must not be null");
        Objects.requireNonNull(detectedAt, "Reconciliation detection time must not be null");
    }
}
