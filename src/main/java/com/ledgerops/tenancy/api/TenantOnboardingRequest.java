package com.ledgerops.tenancy.api;

import java.util.Currency;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

public record TenantOnboardingRequest(
        String name,
        Currency defaultCurrency,
        Locale defaultLocale,
        UUID correlationId,
        UUID operationId
) {

    public TenantOnboardingRequest {
        Objects.requireNonNull(name, "Tenant name must not be null");
        Objects.requireNonNull(defaultCurrency, "Default currency must not be null");
        Objects.requireNonNull(defaultLocale, "Default locale must not be null");
        Objects.requireNonNull(correlationId, "Correlation ID must not be null");
        Objects.requireNonNull(operationId, "Operation ID must not be null");
    }
}
