package com.ledgerops.casework.api;

import java.util.Objects;
import java.util.UUID;

public record CaseCloseRequest(UUID tenantId, UUID caseId, UUID actorId,
                               String reason, UUID correlationId) {
    public CaseCloseRequest {
        Objects.requireNonNull(tenantId, "Tenant ID must not be null");
        Objects.requireNonNull(caseId, "Case ID must not be null");
        Objects.requireNonNull(actorId, "Actor ID must not be null");
        Objects.requireNonNull(correlationId, "Correlation ID must not be null");
        CaseAssignmentRequest.requireText(reason, "Closure reason");
    }
}
