package com.ledgerops.reconciliation.domain;

public enum ReconciliationStatus {
    NOT_APPLICABLE,
    AWAITING_BATCH,
    PENDING,
    MATCHED,
    DISCREPANCY
}
