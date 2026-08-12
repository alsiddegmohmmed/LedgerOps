package com.ledgerops.ledger.api;

public final class SettlementCorrectionLedgerException extends RuntimeException {

    private final SettlementCorrectionLedgerError error;

    public SettlementCorrectionLedgerException(
            SettlementCorrectionLedgerError error,
            String message
    ) {
        super(message);
        this.error = error;
    }

    public SettlementCorrectionLedgerException(
            SettlementCorrectionLedgerError error,
            String message,
            Throwable cause
    ) {
        super(message, cause);
        this.error = error;
    }

    public SettlementCorrectionLedgerError error() {
        return error;
    }
}
