package com.ledgerops.administration.api;

import java.util.Currency;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

public record TenantOnboardingCommand(
        String tenantName,
        Currency defaultCurrency,
        Locale defaultLocale,
        String merchantName,
        String initialAdminEmail,
        String invitationTokenHash,
        String actorIssuer,
        String actorSubject,
        UUID correlationId,
        UUID operationId
) {

    public TenantOnboardingCommand {
        Objects.requireNonNull(tenantName, "Tenant name must not be null");
        Objects.requireNonNull(defaultCurrency, "Default currency must not be null");
        Objects.requireNonNull(defaultLocale, "Default locale must not be null");
        Objects.requireNonNull(merchantName, "Merchant name must not be null");
        Objects.requireNonNull(initialAdminEmail, "Initial Admin email must not be null");
        Objects.requireNonNull(invitationTokenHash, "Invitation token hash must not be null");
        Objects.requireNonNull(actorIssuer, "Actor issuer must not be null");
        Objects.requireNonNull(actorSubject, "Actor subject must not be null");
        Objects.requireNonNull(correlationId, "Correlation ID must not be null");
        Objects.requireNonNull(operationId, "Operation ID must not be null");
    }
}
