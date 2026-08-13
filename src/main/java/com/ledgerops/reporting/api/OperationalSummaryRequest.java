package com.ledgerops.reporting.api;

import com.ledgerops.identity.api.AuthorizedRequestContext;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record OperationalSummaryRequest(
        UUID tenantId,
        Instant fromInclusive,
        Instant toExclusive,
        Set<UUID> merchantIds,
        AuthorizedRequestContext authorization
) {

    public OperationalSummaryRequest {
        Objects.requireNonNull(tenantId, "Summary Tenant ID must not be null");
        Objects.requireNonNull(fromInclusive, "Summary period start must not be null");
        Objects.requireNonNull(toExclusive, "Summary period end must not be null");
        Objects.requireNonNull(authorization, "Summary authorization must not be null");
        if (!fromInclusive.isBefore(toExclusive)) {
            throw new IllegalArgumentException("Summary period start must be before its exclusive end");
        }
        if (!tenantId.equals(authorization.tenantId())) {
            throw new IllegalArgumentException("Summary Tenant does not match authorization context");
        }
        merchantIds = Set.copyOf(Objects.requireNonNull(merchantIds,
                "Summary Merchant IDs must not be null"));
    }
}
