package com.ledgerops.ledger.api;

public final class SettlementLedgerException extends RuntimeException {

    private final SettlementLedgerError error;

    public SettlementLedgerException(SettlementLedgerError error, String message) {
        super(message);
        this.error = error;
    }

    public SettlementLedgerException(SettlementLedgerError error, String message, Throwable cause) {
        super(message, cause);
        this.error = error;
    }

    public SettlementLedgerError error() {
        return error;
    }
}
