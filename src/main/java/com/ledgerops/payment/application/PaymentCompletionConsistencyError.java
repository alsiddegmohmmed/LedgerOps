package com.ledgerops.payment.application;

public enum PaymentCompletionConsistencyError {
    PROCESSING_WITH_EXISTING_POSTING,
    COMPLETED_WITHOUT_POSTING,
    COMPLETED_WITH_MISMATCHED_POSTING,
    NEW_POSTING_MISMATCH
}
