package com.ledgerops.merchant.api;

import com.ledgerops.tenancy.api.TenantReference;

import java.util.Objects;
import java.util.UUID;

public record MerchantOnboardingRequest(
        TenantReference tenant,
        String name,
        UUID correlationId,
        UUID operationId
) {

    public MerchantOnboardingRequest {
        Objects.requireNonNull(tenant, "Tenant reference must not be null");
        Objects.requireNonNull(name, "Merchant name must not be null");
        Objects.requireNonNull(correlationId, "Correlation ID must not be null");
        Objects.requireNonNull(operationId, "Operation ID must not be null");
    }
}
