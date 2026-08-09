package com.ledgerops.administration.api;

import com.ledgerops.identity.api.AuthenticatedPrincipal;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Currency;
import java.util.IllformedLocaleException;
import java.util.Locale;
import java.util.UUID;

record TenantOnboardingHttpRequest(
        @NotBlank @Size(max = 120) String name,
        @NotBlank @Pattern(regexp = "[A-Z]{3}") String defaultCurrency,
        @NotBlank @Size(max = 35) String defaultLocale,
        @NotBlank @Size(max = 120) String merchantName,
        @NotBlank @Email @Size(max = 254) String initialAdminEmail,
        @NotBlank @Pattern(regexp = "[0-9A-Fa-f]{64}") String invitationTokenHash
) {

    TenantOnboardingCommand toCommand(
            AuthenticatedPrincipal actor,
            UUID correlationId,
            UUID operationId
    ) {
        try {
            return new TenantOnboardingCommand(
                    name,
                    Currency.getInstance(defaultCurrency),
                    new Locale.Builder().setLanguageTag(defaultLocale).build(),
                    merchantName,
                    initialAdminEmail,
                    invitationTokenHash,
                    actor.issuer(),
                    actor.subject(),
                    correlationId,
                    operationId
            );
        } catch (IllegalArgumentException | IllformedLocaleException exception) {
            throw new InvalidAdministrationRequestException(
                    "Currency or locale is not supported", exception);
        }
    }
}
