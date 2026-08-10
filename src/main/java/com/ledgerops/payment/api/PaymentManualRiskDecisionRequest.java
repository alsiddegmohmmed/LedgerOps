package com.ledgerops.payment.api;

import java.util.Objects;
import java.util.UUID;

public record PaymentManualRiskDecisionRequest(
        UUID tenantId,
        UUID reviewId,
        PaymentManualRiskDecision decision,
        UUID analystId,
        String reason,
        UUID correlationId,
        UUID causationId
) {
    public PaymentManualRiskDecisionRequest {
        Objects.requireNonNull(tenantId, "Tenant ID must not be null");
        Objects.requireNonNull(reviewId, "Risk review ID must not be null");
        Objects.requireNonNull(decision, "Decision must not be null");
        Objects.requireNonNull(analystId, "Analyst ID must not be null");
        Objects.requireNonNull(correlationId, "Correlation ID must not be null");
        Objects.requireNonNull(causationId, "Causation ID must not be null");
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("Decision reason must not be blank");
    }
}
