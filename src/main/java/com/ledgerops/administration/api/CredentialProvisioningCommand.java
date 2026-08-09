package com.ledgerops.administration.api;

import com.ledgerops.identity.api.AuthenticatedPrincipal;
import com.ledgerops.identity.api.AuthorizedRequestContext;

import java.util.Objects;
import java.util.Locale;
import java.util.UUID;

public record CredentialProvisioningCommand(
        UUID tenantId,
        UUID merchantId,
        String label,
        boolean confirmation,
        String reason,
        AuthorizedRequestContext authorization,
        AuthenticatedPrincipal actor
) {

    public CredentialProvisioningCommand {
        Objects.requireNonNull(tenantId, "Tenant ID must not be null");
        Objects.requireNonNull(merchantId, "Merchant ID must not be null");
        label = requireText(label, "Credential label");
        reason = requireReason(reason);
        Objects.requireNonNull(authorization, "Authorization context must not be null");
        Objects.requireNonNull(actor, "Actor must not be null");
    }

    static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    static String requireReason(String value) {
        String reason = requireText(value, "Credential action reason");
        if (reason.length() > 512) {
            throw new IllegalArgumentException(
                    "Credential action reason must be at most 512 characters");
        }
        String normalized = reason.toLowerCase(Locale.ROOT);
        for (String forbidden : new String[]{
                "password",
                "bearer ",
                "authorization:",
                "access_token",
                "refresh_token",
                "client_secret",
                "private_key",
                "cookie:"
        }) {
            if (normalized.contains(forbidden)) {
                throw new IllegalArgumentException(
                        "Credential action reason contains prohibited sensitive content");
            }
        }
        return reason;
    }
}
