package com.ledgerops.casework.api;

import com.ledgerops.casework.domain.CaseStatus;

import java.util.Objects;
import java.util.UUID;

public record CaseTransitionRequest(UUID tenantId, UUID caseId, CaseStatus target,
                                    UUID actorId, String reason, UUID correlationId,
                                    boolean confirmation) {
    public CaseTransitionRequest {
        Objects.requireNonNull(tenantId, "Tenant ID must not be null");
        Objects.requireNonNull(caseId, "Case ID must not be null");
        Objects.requireNonNull(target, "Target status must not be null");
        Objects.requireNonNull(actorId, "Actor ID must not be null");
        Objects.requireNonNull(correlationId, "Correlation ID must not be null");
        CaseAssignmentRequest.requireText(reason, "Transition reason");
        if (target == CaseStatus.REOPENED && !confirmation) {
            throw new IllegalArgumentException("Case reopening requires explicit confirmation");
        }
    }
}
