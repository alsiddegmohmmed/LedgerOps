package com.ledgerops.casework.api;

import com.ledgerops.casework.domain.CaseSeverity;
import com.ledgerops.casework.domain.CaseSourceCategory;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record CaseCreationRequest(
        UUID caseId,
        UUID tenantId,
        CaseSourceCategory sourceCategory,
        UUID sourceId,
        UUID relatedPaymentId,
        CaseSeverity severity,
        Instant dueAt
) {
    public CaseCreationRequest {
        Objects.requireNonNull(caseId, "Case ID must not be null");
        Objects.requireNonNull(tenantId, "Tenant ID must not be null");
        Objects.requireNonNull(sourceCategory, "Case source category must not be null");
        Objects.requireNonNull(sourceId, "Case source ID must not be null");
        Objects.requireNonNull(severity, "Case severity must not be null");
        Objects.requireNonNull(dueAt, "Case due time must not be null");
    }
}
