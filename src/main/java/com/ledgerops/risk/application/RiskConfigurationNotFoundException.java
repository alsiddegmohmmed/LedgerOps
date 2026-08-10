package com.ledgerops.risk.application;

import java.util.UUID;

public final class RiskConfigurationNotFoundException extends RuntimeException {

    public RiskConfigurationNotFoundException(UUID tenantId) {
        super("No Risk configuration exists for Tenant " + tenantId);
    }
}
