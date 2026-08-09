package com.ledgerops.administration.api;

import com.ledgerops.identity.api.AuthorizedRequestContext;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record CredentialMetadataPageQuery(
        UUID tenantId,
        UUID merchantId,
        String status,
        int limit,
        String cursor,
        AuthorizedRequestContext authorization
) {

    private static final Set<String> STATUSES = Set.of(
            "PROVISIONING", "ACTIVE", "FAILED", "REVOKED");

    public CredentialMetadataPageQuery {
        Objects.requireNonNull(tenantId, "Tenant ID must not be null");
        Objects.requireNonNull(authorization, "Authorization context must not be null");
        if (status != null) {
            status = status.trim().toUpperCase(Locale.ROOT);
            if (status.isBlank()) {
                status = null;
            } else if (!STATUSES.contains(status)) {
                throw new IllegalArgumentException("Unsupported credential status filter");
            }
        }
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("Credential page limit must be between 1 and 100");
        }
        if (cursor != null) {
            cursor = cursor.trim();
            if (cursor.isBlank()) {
                cursor = null;
            }
        }
    }
}
