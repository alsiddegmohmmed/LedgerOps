package com.ledgerops.risk.api;

import java.util.Objects;

public final class RiskProcessingException extends RuntimeException {

    private final RiskProcessingError error;

    public RiskProcessingException(
            RiskProcessingError error,
            String message,
            Throwable cause
    ) {
        super(message, cause);
        this.error = Objects.requireNonNull(error, "Risk processing error must not be null");
    }

    public RiskProcessingError error() {
        return error;
    }
}
