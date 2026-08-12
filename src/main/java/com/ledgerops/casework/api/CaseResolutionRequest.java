package com.ledgerops.casework.api;

import com.ledgerops.casework.domain.CaseResolution;

import java.util.Objects;
import java.util.UUID;

public record CaseResolutionRequest(UUID tenantId, UUID caseId, CaseResolution resolution,
                                    UUID actorId, String note, UUID correlationId,
                                    UUID causationId, boolean confirmation) {
    public CaseResolutionRequest {
        Objects.requireNonNull(tenantId, "Tenant ID must not be null");
        Objects.requireNonNull(caseId, "Case ID must not be null");
        Objects.requireNonNull(resolution, "Case resolution must not be null");
        Objects.requireNonNull(actorId, "Actor ID must not be null");
        Objects.requireNonNull(correlationId, "Correlation ID must not be null");
        Objects.requireNonNull(causationId, "Causation ID must not be null");
        CaseAssignmentRequest.requireText(note, "Resolution note");
        if (!confirmation) {
            throw new IllegalArgumentException("Case resolution requires explicit confirmation");
        }
    }
}
