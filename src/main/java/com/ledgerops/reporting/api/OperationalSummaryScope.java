package com.ledgerops.reporting.api;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record OperationalSummaryScope(
        OperationalSummaryScopeMode mode,
        Set<UUID> merchantIds
) {

    public OperationalSummaryScope {
        Objects.requireNonNull(mode, "Summary scope mode must not be null");
        merchantIds = Set.copyOf(Objects.requireNonNull(merchantIds, "Summary Merchant IDs must not be null"));
        if (mode == OperationalSummaryScopeMode.MERCHANT_SET && merchantIds.isEmpty()) {
            throw new IllegalArgumentException("Merchant-scoped summary must contain Merchant IDs");
        }
    }
}
