package com.ledgerops.risk.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Minimal Risk review fact needed for the operational-summary metric. */
public record RiskReviewOperationalSummary(
        UUID reviewId,
        UUID tenantId,
        UUID paymentId,
        UUID merchantId,
        Instant createdAt
) {

    public RiskReviewOperationalSummary {
        Objects.requireNonNull(reviewId, "Risk review ID must not be null");
        Objects.requireNonNull(tenantId, "Risk review Tenant ID must not be null");
        Objects.requireNonNull(paymentId, "Risk review Payment ID must not be null");
        Objects.requireNonNull(merchantId, "Risk review Merchant ID must not be null");
        Objects.requireNonNull(createdAt, "Risk review creation time must not be null");
    }
}
