package com.ledgerops.identity.api;

import com.ledgerops.identity.domain.ServiceCredentialStatus;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * Tenant-scoped keyset query for non-secret credential metadata.
 */
public record ServiceCredentialPageQuery(
        UUID tenantId,
        UUID merchantId,
        String status,
        Instant beforeCreatedAt,
        UUID beforeCredentialId,
        int limit
) {

    public ServiceCredentialPageQuery {
        Objects.requireNonNull(tenantId, "Tenant ID must not be null");
        if (status != null) {
            status = status.trim().toUpperCase(Locale.ROOT);
            if (status.isBlank()) {
                status = null;
            } else {
                try {
                    ServiceCredentialStatus.valueOf(status);
                } catch (IllegalArgumentException exception) {
                    throw new IllegalArgumentException(
                            "Unsupported credential status filter", exception);
                }
            }
        }
        if ((beforeCreatedAt == null) != (beforeCredentialId == null)) {
            throw new IllegalArgumentException(
                    "Credential page position requires both timestamp and credential ID");
        }
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("Credential page limit must be between 1 and 100");
        }
    }
}
