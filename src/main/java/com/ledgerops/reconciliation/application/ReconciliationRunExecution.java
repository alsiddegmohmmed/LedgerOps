package com.ledgerops.reconciliation.application;

import com.ledgerops.reconciliation.domain.ReconciliationRunCounts;
import com.ledgerops.reconciliation.domain.ReconciliationRunStatus;

import java.util.UUID;

public record ReconciliationRunExecution(
        UUID runId,
        UUID snapshotId,
        int runNumber,
        ReconciliationRunStatus status,
        ReconciliationRunCounts counts
) {
}
