package com.ledgerops.ledger.application;

public final class LedgerPostingException extends RuntimeException {

    private final LedgerPostingError error;

    public LedgerPostingException(LedgerPostingError error, String message) {
        super(message);
        this.error = error;
    }

    public LedgerPostingError error() {
        return error;
    }
}
