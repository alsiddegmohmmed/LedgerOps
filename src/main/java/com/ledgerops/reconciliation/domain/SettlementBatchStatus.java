package com.ledgerops.reconciliation.domain;

public enum SettlementBatchStatus {
    RECEIVED,
    VALIDATING,
    READY,
    PROCESSING,
    COMPLETED,
    COMPLETED_WITH_DISCREPANCIES,
    FAILED
}
