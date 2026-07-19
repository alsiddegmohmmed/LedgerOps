package com.ledgerops.tenancy.api;

import com.ledgerops.tenancy.application.CreateTenantCommand;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Currency;
import java.util.IllformedLocaleException;
import java.util.Locale;

record CreateTenantRequest(
        @NotBlank @Size(max = 120) String name,
        @NotBlank @Pattern(regexp = "[A-Z]{3}") String defaultCurrency,
        @NotBlank @Size(max = 35) String defaultLocale
) {

    CreateTenantCommand toCommand() {
        try {
            return new CreateTenantCommand(
                    name,
                    Currency.getInstance(defaultCurrency),
                    new Locale.Builder().setLanguageTag(defaultLocale).build()
            );
        } catch (IllegalArgumentException | IllformedLocaleException exception) {
            throw new InvalidTenantRequestException(
                    "Currency or locale is not supported",
                    exception
            );
        }
    }
}
