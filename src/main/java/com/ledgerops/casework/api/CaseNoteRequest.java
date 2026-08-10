package com.ledgerops.casework.api;

import java.util.Objects;
import java.util.UUID;

public record CaseNoteRequest(
        UUID tenantId,
        UUID caseId,
        UUID actorId,
        String note,
        UUID correlationId
) {
    public CaseNoteRequest {
        Objects.requireNonNull(tenantId, "Tenant ID must not be null");
        Objects.requireNonNull(caseId, "Case ID must not be null");
        Objects.requireNonNull(actorId, "Actor ID must not be null");
        Objects.requireNonNull(correlationId, "Correlation ID must not be null");
        if (note == null || note.isBlank()) {
            throw new IllegalArgumentException("Case note must not be blank");
        }
        note = note.trim();
    }
}
