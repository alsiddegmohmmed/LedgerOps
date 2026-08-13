package com.ledgerops.reporting.api;

import com.ledgerops.identity.api.AuthorizedRequestContext;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record OperationalSummaryRecordsRequest(
        UUID tenantId,
        OperationalSummaryMetricCode metric,
        Instant fromInclusive,
        Instant toExclusive,
        Set<UUID> merchantIds,
        String after,
        int limit,
        AuthorizedRequestContext authorization
) {

    public static final int MAXIMUM_LIMIT = 100;

    public OperationalSummaryRecordsRequest {
        Objects.requireNonNull(tenantId, "Summary Tenant ID must not be null");
        Objects.requireNonNull(metric, "Summary metric must not be null");
        Objects.requireNonNull(fromInclusive, "Summary period start must not be null");
        Objects.requireNonNull(toExclusive, "Summary period end must not be null");
        Objects.requireNonNull(authorization, "Summary authorization must not be null");
        if (!fromInclusive.isBefore(toExclusive)) {
            throw new IllegalArgumentException("Summary period start must be before its exclusive end");
        }
        if (limit < 1 || limit > MAXIMUM_LIMIT) {
            throw new IllegalArgumentException(
                    "Summary record limit must be between 1 and " + MAXIMUM_LIMIT);
        }
        if (!tenantId.equals(authorization.tenantId())) {
            throw new IllegalArgumentException("Summary Tenant does not match authorization context");
        }
        merchantIds = Set.copyOf(Objects.requireNonNull(merchantIds,
                "Summary Merchant IDs must not be null"));
        if (after != null) {
            after = after.trim();
            if (after.isBlank()) {
                after = null;
            }
        }
    }
}
