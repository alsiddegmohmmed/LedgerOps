package com.ledgerops.merchant.domain;

import com.ledgerops.tenancy.api.TenantReference;

import java.util.Objects;

public final class Merchant {

    private static final int MAX_NAME_LENGTH = 120;

    private final MerchantId id;
    private final TenantReference tenantReference;
    private final String name;
    private final MerchantStatus status;

    public Merchant(
            MerchantId id,
            TenantReference tenantReference,
            String name,
            MerchantStatus status
    ) {
        this.id = Objects.requireNonNull(id, "Merchant ID must not be null");
        this.tenantReference = Objects.requireNonNull(
                tenantReference,
                "Tenant reference must not be null"
        );
        this.name = requireName(name);
        this.status = Objects.requireNonNull(status, "Merchant status must not be null");
    }

    public MerchantId id() {
        return id;
    }

    public TenantReference tenantReference() {
        return tenantReference;
    }

    public String name() {
        return name;
    }

    public MerchantStatus status() {
        return status;
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Merchant name must not be blank");
        }

        String normalizedName = name.trim();

        if (normalizedName.length() > MAX_NAME_LENGTH) {
            throw new IllegalArgumentException(
                    "Merchant name must not exceed " + MAX_NAME_LENGTH + " characters"
            );
        }

        return normalizedName;
    }
}
