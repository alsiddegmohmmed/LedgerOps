package com.ledgerops.tenancy.domain;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Currency;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

public record TenantConfiguration(
        TenantId tenantId,
        long version,
        Set<Currency> allowedCurrencies,
        Locale defaultLocale,
        ZoneId timezone,
        String displaySettingsJson,
        Instant createdAt,
        String actorIdentity
) {

    public TenantConfiguration {
        Objects.requireNonNull(tenantId, "Tenant ID must not be null");
        if (version < 1) {
            throw new IllegalArgumentException("Tenant configuration version must be positive");
        }
        Objects.requireNonNull(allowedCurrencies, "Allowed currencies must not be null");
        if (allowedCurrencies.isEmpty()) {
            throw new IllegalArgumentException("At least one allowed currency is required");
        }
        allowedCurrencies = Set.copyOf(new LinkedHashSet<>(allowedCurrencies));
        for (Currency currency : allowedCurrencies) {
            Objects.requireNonNull(currency, "Allowed currency must not be null");
        }
        Objects.requireNonNull(defaultLocale, "Default locale must not be null");
        Objects.requireNonNull(timezone, "Timezone must not be null");
        displaySettingsJson = requireJsonObject(displaySettingsJson);
        Objects.requireNonNull(createdAt, "Configuration creation time must not be null");
        actorIdentity = requireText(actorIdentity, "Actor identity");
    }

    private static String requireJsonObject(String value) {
        String normalized = requireText(value, "Display settings");
        if (!normalized.startsWith("{") || !normalized.endsWith("}")) {
            throw new IllegalArgumentException("Display settings must be a JSON object");
        }
        return normalized;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
