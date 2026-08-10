package com.ledgerops.risk.api;

import java.util.Objects;
import java.util.UUID;

public record RiskReviewDecisionRequest(
        UUID tenantId,
        UUID reviewId,
        UUID paymentId,
        UUID analystId,
        RiskReviewDecision decision,
        String reason,
        UUID caseId,
        UUID correlationId,
        UUID causationId
) {

    public RiskReviewDecisionRequest {
        Objects.requireNonNull(tenantId, "Tenant ID must not be null");
        Objects.requireNonNull(reviewId, "Risk review ID must not be null");
        Objects.requireNonNull(paymentId, "Payment ID must not be null");
        Objects.requireNonNull(analystId, "Analyst ID must not be null");
        Objects.requireNonNull(decision, "Risk review decision must not be null");
        Objects.requireNonNull(correlationId, "Correlation ID must not be null");
        Objects.requireNonNull(causationId, "Causation ID must not be null");
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Risk decision reason must not be blank");
        }
        if (decision == RiskReviewDecision.ESCALATE && caseId == null) {
            throw new IllegalArgumentException("Escalation requires a stable case ID");
        }
        if (decision != RiskReviewDecision.ESCALATE && caseId != null) {
            throw new IllegalArgumentException("Only escalation may carry a case ID");
        }
    }
}
