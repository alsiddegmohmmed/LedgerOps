package com.ledgerops.risk.api;

import java.util.Objects;

public final class RiskConfigurationException extends RuntimeException {

    private final RiskConfigurationError error;

    public RiskConfigurationException(RiskConfigurationError error, String message) {
        super(message);
        this.error = Objects.requireNonNull(error, "Risk configuration error must not be null");
    }

    public RiskConfigurationError error() {
        return error;
    }
}
