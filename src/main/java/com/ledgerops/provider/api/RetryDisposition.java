package com.ledgerops.provider.api;

public enum RetryDisposition {
    SAFE_TO_RESUBMIT,
    STATUS_RECOVERY_REQUIRED,
    NOT_RETRYABLE
}
