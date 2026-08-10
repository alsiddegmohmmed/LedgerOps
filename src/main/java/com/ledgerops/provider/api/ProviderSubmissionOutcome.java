package com.ledgerops.provider.api;

public enum ProviderSubmissionOutcome {
    SUCCESS,
    DECLINE,
    ACCEPTED,
    PENDING,
    TIMEOUT_AFTER_ACCEPTANCE,
    TEMPORARY_FAILURE_SAFE_TO_RESUBMIT,
    TEMPORARY_FAILURE_STATUS_RECOVERY
}
