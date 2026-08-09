package com.ledgerops.tenancy.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerops.identity.api.AuthenticatedPrincipal;
import com.ledgerops.identity.api.AuthorizedRequestContext;
import com.ledgerops.tenancy.application.TenantConfigurationCommand;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.time.ZoneId;
import java.util.Currency;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

record TenantConfigurationRequest(
        @NotEmpty Set<@NotBlank String> allowedCurrencies,
        @NotBlank String defaultLocale,
        @NotBlank String timezone,
        @NotNull Map<String, Object> displaySettings
) {

    TenantConfigurationCommand toCommand(
            TenantReference tenant,
            AuthorizedRequestContext context,
            AuthenticatedPrincipal actor,
            ObjectMapper objectMapper
    ) {
        if (displaySettings == null) {
            throw new InvalidTenantConfigurationRequestException(
                    "displaySettings must be a JSON object"
            );
        }

        Set<Currency> currencies = new LinkedHashSet<>();
        for (String value : allowedCurrencies) {
            try {
                currencies.add(Currency.getInstance(value.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException exception) {
                throw new InvalidTenantConfigurationRequestException(
                        "allowedCurrencies contains an invalid ISO 4217 code: " + value,
                        exception
                );
            }
        }

        Locale locale = Locale.forLanguageTag(defaultLocale.trim());
        if (locale.getLanguage().isBlank()) {
            throw new InvalidTenantConfigurationRequestException(
                    "defaultLocale must be a valid BCP 47 language tag"
            );
        }

        ZoneId zone;
        try {
            zone = ZoneId.of(timezone.trim());
        } catch (java.time.DateTimeException exception) {
            throw new InvalidTenantConfigurationRequestException(
                    "timezone must be a valid IANA time-zone ID",
                    exception
            );
        }

        try {
            return new TenantConfigurationCommand(
                    tenant,
                    context,
                    actor,
                    currencies,
                    locale,
                    zone,
                    objectMapper.writeValueAsString(displaySettings)
            );
        } catch (Exception exception) {
            throw new InvalidTenantConfigurationRequestException(
                    "displaySettings could not be encoded",
                    exception
            );
        }
    }
}
