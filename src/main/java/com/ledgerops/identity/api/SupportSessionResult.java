package com.ledgerops.identity.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record SupportSessionResult(
        UUID supportSessionId,
        UUID tenantId,
        Instant startedAt,
        Instant expiresAt,
        String permission
) {

    public SupportSessionResult {
        Objects.requireNonNull(supportSessionId, "Support session ID must not be null");
        Objects.requireNonNull(tenantId, "Tenant ID must not be null");
        Objects.requireNonNull(startedAt, "Start time must not be null");
        Objects.requireNonNull(expiresAt, "Expiry time must not be null");
        if (!"support:tenant-read".equals(permission)) {
            throw new IllegalArgumentException("Support sessions grant only support:tenant-read");
        }
    }
}
