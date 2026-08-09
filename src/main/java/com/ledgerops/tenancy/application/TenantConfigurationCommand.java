package com.ledgerops.tenancy.application;

import com.ledgerops.identity.api.AuthenticatedPrincipal;
import com.ledgerops.identity.api.AuthorizedRequestContext;
import com.ledgerops.tenancy.api.TenantReference;

import java.time.ZoneId;
import java.util.Currency;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

public record TenantConfigurationCommand(
        TenantReference tenant,
        AuthorizedRequestContext context,
        AuthenticatedPrincipal actor,
        Set<Currency> allowedCurrencies,
        Locale defaultLocale,
        ZoneId timezone,
        String displaySettingsJson,
        boolean confirmation,
        String reason
) {

    public TenantConfigurationCommand {
        Objects.requireNonNull(tenant, "Tenant reference must not be null");
        Objects.requireNonNull(context, "Authorized context must not be null");
        Objects.requireNonNull(actor, "Authenticated actor must not be null");
        Objects.requireNonNull(allowedCurrencies, "Allowed currencies must not be null");
        Objects.requireNonNull(defaultLocale, "Default locale must not be null");
        Objects.requireNonNull(timezone, "Timezone must not be null");
        Objects.requireNonNull(displaySettingsJson, "Display settings must not be null");
        if (!confirmation) {
            throw new IllegalArgumentException(
                    "Tenant configuration changes require explicit confirmation");
        }
        reason = requireReason(reason);
    }

    private static String requireReason(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "Tenant configuration change reason must not be blank");
        }
        return value.trim();
    }
}
