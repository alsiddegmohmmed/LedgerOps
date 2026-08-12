package com.ledgerops.reconciliation.api;

public final class ReconciliationCorrectionException extends RuntimeException {

    private final ReconciliationCorrectionError error;

    public ReconciliationCorrectionException(
            ReconciliationCorrectionError error,
            String message
    ) {
        super(message);
        this.error = error;
    }

    public ReconciliationCorrectionError error() {
        return error;
    }
}
