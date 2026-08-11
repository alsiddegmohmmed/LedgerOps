package com.ledgerops.reconciliation.api;

import java.time.Instant;
import java.util.UUID;

public record ReconciliationCurrentRunSnapshot(
        UUID tenantId,
        UUID batchFamilyId,
        UUID runId,
        Instant promotedAt
) {
}
