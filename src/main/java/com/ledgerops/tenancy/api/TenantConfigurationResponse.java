package com.ledgerops.tenancy.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerops.tenancy.domain.TenantConfiguration;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

record TenantConfigurationResponse(
        UUID tenantId,
        long version,
        Set<String> allowedCurrencies,
        String defaultLocale,
        String timezone,
        Map<String, Object> displaySettings,
        Instant createdAt
) {

    static TenantConfigurationResponse from(
            TenantConfiguration configuration,
            ObjectMapper objectMapper
    ) {
        try {
            return new TenantConfigurationResponse(
                    configuration.tenantId().value(),
                    configuration.version(),
                    configuration.allowedCurrencies().stream()
                            .map(java.util.Currency::getCurrencyCode)
                            .collect(java.util.stream.Collectors.toUnmodifiableSet()),
                    configuration.defaultLocale().toLanguageTag(),
                    configuration.timezone().getId(),
                    objectMapper.readValue(
                            configuration.displaySettingsJson(),
                            new TypeReference<Map<String, Object>>() { }
                    ),
                    configuration.createdAt()
            );
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Persisted Tenant display settings are not valid JSON",
                    exception
            );
        }
    }
}
