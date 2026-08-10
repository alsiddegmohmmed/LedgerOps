package com.ledgerops.payment.application;

public enum ReversalProviderResultOutcome {
    COMPLETED,
    FAILED,
    NON_FINAL,
    REPLAY,
    DUPLICATE_MESSAGE
}
