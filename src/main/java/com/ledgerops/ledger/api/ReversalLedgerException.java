package com.ledgerops.ledger.api;

public final class ReversalLedgerException extends RuntimeException {

    private final ReversalLedgerError error;

    public ReversalLedgerException(ReversalLedgerError error, String message) {
        super(message);
        this.error = error;
    }

    public ReversalLedgerException(
            ReversalLedgerError error,
            String message,
            Throwable cause
    ) {
        super(message, cause);
        this.error = error;
    }

    public ReversalLedgerError error() {
        return error;
    }
}
