package com.ledgerops.tenancy.api;

import com.ledgerops.tenancy.domain.Tenant;
import com.ledgerops.tenancy.domain.TenantStatus;

import java.util.UUID;

record TenantResponse(
        UUID id,
        String name,
        String defaultCurrency,
        String defaultLocale,
        TenantStatus status
) {

    static TenantResponse from(Tenant tenant) {
        return new TenantResponse(
                tenant.id().value(),
                tenant.name(),
                tenant.defaultCurrency().getCurrencyCode(),
                tenant.defaultLocale().toLanguageTag(),
                tenant.status()
        );
    }
}
