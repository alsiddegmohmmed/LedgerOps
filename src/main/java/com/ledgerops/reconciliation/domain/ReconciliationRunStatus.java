package com.ledgerops.reconciliation.domain;

public enum ReconciliationRunStatus {
    QUEUED,
    RUNNING,
    COMPLETED,
    COMPLETED_WITH_DISCREPANCIES,
    FAILED,
    CANCELLED
}
