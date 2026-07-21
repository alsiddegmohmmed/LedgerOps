package com.ledgerops.payment.application;

public enum PaymentProviderResultOutcome {
    COMPLETED,
    FAILED,
    NON_FINAL,
    TERMINAL_IGNORED,
    REPLAY,
    DUPLICATE_MESSAGE
}
