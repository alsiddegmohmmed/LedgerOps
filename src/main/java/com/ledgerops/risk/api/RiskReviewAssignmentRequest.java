package com.ledgerops.risk.api;

import java.util.Objects;
import java.util.UUID;

public record RiskReviewAssignmentRequest(
        UUID tenantId,
        UUID reviewId,
        UUID assignedAnalystId,
        UUID actorId,
        int priority,
        String reason,
        UUID correlationId
) {

    public RiskReviewAssignmentRequest {
        Objects.requireNonNull(tenantId, "Tenant ID must not be null");
        Objects.requireNonNull(reviewId, "Risk review ID must not be null");
        Objects.requireNonNull(assignedAnalystId, "Assigned analyst ID must not be null");
        Objects.requireNonNull(actorId, "Actor ID must not be null");
        Objects.requireNonNull(correlationId, "Correlation ID must not be null");
        if (priority < 0) {
            throw new IllegalArgumentException("Risk review priority must not be negative");
        }
        requireReason(reason);
    }

    private static void requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Assignment reason must not be blank");
        }
    }
}
