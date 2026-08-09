package com.ledgerops.tenancy.domain;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

public record OperationalContact(
        TenantId tenantId,
        UUID contactId,
        long version,
        String displayName,
        String email,
        String purpose,
        boolean active,
        Instant createdAt,
        String actorIdentity
) {

    private static final Pattern EMAIL = Pattern.compile(
            "^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

    public OperationalContact {
        Objects.requireNonNull(tenantId, "Tenant ID must not be null");
        Objects.requireNonNull(contactId, "Contact ID must not be null");
        if (version < 1) {
            throw new IllegalArgumentException("Operational contact version must be positive");
        }
        displayName = requireText(displayName, "Display name");
        email = requireEmail(email);
        purpose = requireText(purpose, "Contact purpose");
        Objects.requireNonNull(createdAt, "Contact creation time must not be null");
        actorIdentity = requireText(actorIdentity, "Actor identity");
    }

    private static String requireEmail(String value) {
        String normalized = requireText(value, "Contact email").toLowerCase(Locale.ROOT);
        if (!EMAIL.matcher(normalized).matches()) {
            throw new IllegalArgumentException("Contact email is invalid");
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
