package com.ledgerops.reconciliation.api;

import com.ledgerops.reconciliation.domain.ReconciliationRunStatus;

import java.time.Instant;
import java.util.UUID;

public record ReconciliationRunSnapshot(
        UUID runId,
        UUID tenantId,
        UUID batchFamilyId,
        UUID batchVersionId,
        UUID snapshotId,
        int runNumber,
        String rulesVersion,
        Instant sourceCutoff,
        ReconciliationRunStatus status,
        long matchedCount,
        long unmatchedCount,
        long discrepancyCount,
        Instant createdAt,
        Instant startedAt,
        Instant terminalAt,
        String failureReason
) {
}
