package com.ledgerops.risk.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record RiskReviewSnapshot(
        UUID reviewId,
        UUID tenantId,
        UUID paymentId,
        UUID merchantId,
        UUID evaluationId,
        RiskReviewStatus status,
        UUID assignedAnalystId,
        int priority,
        int slaVersion,
        Instant createdAt,
        Instant dueAt,
        RiskReviewDecision decision,
        String decisionReason,
        UUID caseId,
        Instant decidedAt,
        long version
) {

    public RiskReviewSnapshot {
        Objects.requireNonNull(reviewId, "Risk review ID must not be null");
        Objects.requireNonNull(tenantId, "Tenant ID must not be null");
        Objects.requireNonNull(paymentId, "Payment ID must not be null");
        Objects.requireNonNull(evaluationId, "Evaluation ID must not be null");
        Objects.requireNonNull(status, "Risk review status must not be null");
        Objects.requireNonNull(createdAt, "Risk review creation time must not be null");
        Objects.requireNonNull(dueAt, "Risk review due time must not be null");
        if (priority < 0) {
            throw new IllegalArgumentException("Risk review priority must not be negative");
        }
        if (slaVersion < 1) {
            throw new IllegalArgumentException("Risk review SLA version must be positive");
        }
        if (version < 0) {
            throw new IllegalArgumentException("Risk review version must not be negative");
        }
    }

    public boolean overdueAt(Instant now) {
        return !Objects.requireNonNull(now, "Current time must not be null").isBefore(dueAt);
    }
}
