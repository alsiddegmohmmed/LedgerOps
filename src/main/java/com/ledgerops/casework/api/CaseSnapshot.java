package com.ledgerops.casework.api;

import com.ledgerops.casework.domain.CaseHistoryEntry;
import com.ledgerops.casework.domain.CaseNote;
import com.ledgerops.casework.domain.CaseResolution;
import com.ledgerops.casework.domain.CaseSeverity;
import com.ledgerops.casework.domain.CaseSourceCategory;
import com.ledgerops.casework.domain.CaseStatus;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record CaseSnapshot(
        UUID caseId,
        UUID tenantId,
        CaseSourceCategory sourceCategory,
        UUID sourceId,
        UUID relatedPaymentId,
        CaseSeverity severity,
        Instant createdAt,
        Instant dueAt,
        CaseStatus status,
        UUID ownerId,
        CaseResolution resolution,
        String resolutionNote,
        List<CaseHistoryEntry> history,
        List<CaseNote> notes
) {
    public CaseSnapshot {
        Objects.requireNonNull(caseId, "Case ID must not be null");
        Objects.requireNonNull(tenantId, "Tenant ID must not be null");
        Objects.requireNonNull(sourceCategory, "Case source category must not be null");
        Objects.requireNonNull(sourceId, "Case source ID must not be null");
        Objects.requireNonNull(severity, "Case severity must not be null");
        Objects.requireNonNull(createdAt, "Case creation time must not be null");
        Objects.requireNonNull(dueAt, "Case due time must not be null");
        Objects.requireNonNull(status, "Case status must not be null");
        history = List.copyOf(Objects.requireNonNull(history, "Case history must not be null"));
        notes = List.copyOf(Objects.requireNonNull(notes, "Case notes must not be null"));
    }

    public boolean overdueAt(Instant now) {
        return !Objects.requireNonNull(now, "Current time must not be null").isBefore(dueAt)
                && status != CaseStatus.CLOSED;
    }
}
