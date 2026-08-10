package com.ledgerops.casework.api;

import java.util.Objects;
import java.util.UUID;

public record CaseAssignmentRequest(UUID tenantId, UUID caseId, UUID ownerId,
                                    UUID actorId, String reason, UUID correlationId) {
    public CaseAssignmentRequest {
        Objects.requireNonNull(tenantId, "Tenant ID must not be null");
        Objects.requireNonNull(caseId, "Case ID must not be null");
        Objects.requireNonNull(ownerId, "Owner ID must not be null");
        Objects.requireNonNull(actorId, "Actor ID must not be null");
        Objects.requireNonNull(correlationId, "Correlation ID must not be null");
        requireText(reason, "Assignment reason");
    }
    static void requireText(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + " must not be blank");
    }
}
