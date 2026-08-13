package com.ledgerops.casework.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Minimal current Case fact needed for the operational-summary metric. */
public record CaseOperationalSummary(
        UUID caseId,
        UUID tenantId,
        String sourceType,
        UUID sourceId,
        UUID relatedPaymentId,
        UUID merchantId,
        String currentStatus,
        Instant createdAt
) {

    public CaseOperationalSummary {
        Objects.requireNonNull(caseId, "Case ID must not be null");
        Objects.requireNonNull(tenantId, "Case Tenant ID must not be null");
        Objects.requireNonNull(sourceType, "Case source type must not be null");
        Objects.requireNonNull(sourceId, "Case source ID must not be null");
        Objects.requireNonNull(currentStatus, "Case current status must not be null");
        Objects.requireNonNull(createdAt, "Case creation time must not be null");
        if (!switch (currentStatus) {
            case "OPEN", "INVESTIGATING", "AWAITING_INFORMATION", "REOPENED" -> true;
            default -> false;
        }) {
            throw new IllegalArgumentException(
                    "Operational Case status must be unresolved");
        }
    }
}
