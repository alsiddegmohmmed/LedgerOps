package com.ledgerops.tenancy.application;

import java.util.Currency;
import java.util.Locale;
import java.util.Objects;

public record CreateTenantCommand(
        String name,
        Currency defaultCurrency,
        Locale defaultLocale
) {

    public CreateTenantCommand {
        Objects.requireNonNull(name, "Tenant name must not be null");
        Objects.requireNonNull(defaultCurrency, "Default currency must not be null");
        Objects.requireNonNull(defaultLocale, "Default locale must not be null");
    }
}
