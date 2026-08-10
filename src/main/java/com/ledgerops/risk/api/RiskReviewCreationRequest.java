package com.ledgerops.risk.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record RiskReviewCreationRequest(
        UUID tenantId,
        UUID paymentId,
        UUID merchantId,
        UUID evaluationId,
        int priority,
        int slaVersion,
        Instant createdAt,
        Instant dueAt
) {

    public RiskReviewCreationRequest(
            UUID tenantId,
            UUID paymentId,
            UUID evaluationId,
            int priority,
            int slaVersion,
            Instant createdAt,
            Instant dueAt
    ) {
        this(tenantId, paymentId, null, evaluationId, priority, slaVersion, createdAt, dueAt);
    }

    public RiskReviewCreationRequest {
        Objects.requireNonNull(tenantId, "Tenant ID must not be null");
        Objects.requireNonNull(paymentId, "Payment ID must not be null");
        Objects.requireNonNull(evaluationId, "Evaluation ID must not be null");
        Objects.requireNonNull(createdAt, "Creation time must not be null");
        Objects.requireNonNull(dueAt, "Due time must not be null");
        if (priority < 0) {
            throw new IllegalArgumentException("Risk review priority must not be negative");
        }
        if (slaVersion < 1) {
            throw new IllegalArgumentException("Risk review SLA version must be positive");
        }
        if (dueAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("Risk review due time must not precede creation");
        }
    }
}
