package com.ledgerops.payment.api;

import java.util.Objects;
import java.util.UUID;

public record PaymentCaseResolutionRequest(
        UUID tenantId,
        UUID paymentId,
        UUID riskReviewId,
        UUID caseId,
        RiskPaymentResolution resolution,
        UUID actorId,
        String reason,
        UUID correlationId,
        UUID causationId
) {
    public PaymentCaseResolutionRequest {
        Objects.requireNonNull(tenantId, "Tenant ID must not be null");
        Objects.requireNonNull(paymentId, "Payment ID must not be null");
        Objects.requireNonNull(riskReviewId, "Risk review ID must not be null");
        Objects.requireNonNull(caseId, "Case ID must not be null");
        Objects.requireNonNull(resolution, "Payment resolution must not be null");
        Objects.requireNonNull(actorId, "Actor ID must not be null");
        Objects.requireNonNull(correlationId, "Correlation ID must not be null");
        Objects.requireNonNull(causationId, "Causation ID must not be null");
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("Resolution reason must not be blank");
    }
}
