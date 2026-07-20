package com.ledgerops.ledger.api;

import java.util.Objects;

public final class PaymentSuccessLedgerException extends RuntimeException {

    private final PaymentSuccessLedgerError error;

    public PaymentSuccessLedgerException(
            PaymentSuccessLedgerError error,
            String message
    ) {
        super(message);
        this.error = Objects.requireNonNull(error, "Ledger error must not be null");
    }

    public PaymentSuccessLedgerException(
            PaymentSuccessLedgerError error,
            String message,
            Throwable cause
    ) {
        super(message, cause);
        this.error = Objects.requireNonNull(error, "Ledger error must not be null");
    }

    public PaymentSuccessLedgerError error() {
        return error;
    }
}
