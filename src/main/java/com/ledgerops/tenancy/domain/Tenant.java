package com.ledgerops.tenancy.domain;

import java.util.Currency;
import java.util.Locale;
import java.util.Objects;

public final class Tenant {

    private final TenantId id;
    private final String name;
    private final Currency defaultCurrency;
    private final Locale defaultLocale;
    private final TenantStatus status;

    public Tenant(
            TenantId id,
            String name,
            Currency defaultCurrency,
            Locale defaultLocale,
            TenantStatus status
    ) {
        this.id = Objects.requireNonNull(id, "Tenant ID must not be null");
        this.name = requireName(name);
        this.defaultCurrency =
                Objects.requireNonNull(defaultCurrency, "Default currency must not be null");
        this.defaultLocale =
                Objects.requireNonNull(defaultLocale, "Default locale must not be null");
        this.status = Objects.requireNonNull(status, "Tenant status must not be null");
    }

    public Tenant activate() {
        if (status != TenantStatus.PENDING_ACTIVATION
                && status != TenantStatus.SUSPENDED) {
            throw invalidTransition(TenantStatus.ACTIVE);
        }

        return withStatus(TenantStatus.ACTIVE);
    }

    public Tenant suspend() {
        if (status != TenantStatus.ACTIVE) {
            throw invalidTransition(TenantStatus.SUSPENDED);
        }

        return withStatus(TenantStatus.SUSPENDED);
    }

    public Tenant archive() {
        if (status == TenantStatus.ARCHIVED) {
            throw invalidTransition(TenantStatus.ARCHIVED);
        }

        return withStatus(TenantStatus.ARCHIVED);
    }

    public boolean canCreateOperationalActivity() {
        return status == TenantStatus.ACTIVE;
    }

    public TenantId id() {
        return id;
    }

    public String name() {
        return name;
    }

    public Currency defaultCurrency() {
        return defaultCurrency;
    }

    public Locale defaultLocale() {
        return defaultLocale;
    }

    public TenantStatus status() {
        return status;
    }

    private Tenant withStatus(TenantStatus newStatus) {
        return new Tenant(
                id,
                name,
                defaultCurrency,
                defaultLocale,
                newStatus
        );
    }

    private IllegalStateException invalidTransition(TenantStatus targetStatus) {
        return new IllegalStateException(
                "Tenant cannot transition from " + status + " to " + targetStatus
        );
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Tenant name must not be blank");
        }

        return name.trim();
    }
}
