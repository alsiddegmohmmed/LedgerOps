package com.ledgerops.casework.api;

import com.ledgerops.casework.domain.CorrectionRequestKind;
import com.ledgerops.casework.domain.CorrectionRequestStatus;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record CorrectionRequestSnapshot(
        UUID correctionId,
        UUID tenantId,
        UUID caseId,
        UUID discrepancyId,
        UUID settlementPostingId,
        UUID originalLedgerTransactionId,
        CorrectionRequestKind kind,
        UUID requestedBy,
        String reason,
        Instant requestedAt,
        CorrectionRequestStatus status,
        Instant updatedAt,
        UUID compensationLedgerTransactionId,
        String failureReason
) {

    public CorrectionRequestSnapshot {
        Objects.requireNonNull(correctionId, "Correction ID must not be null");
        Objects.requireNonNull(tenantId, "Tenant ID must not be null");
        Objects.requireNonNull(caseId, "Case ID must not be null");
        Objects.requireNonNull(discrepancyId, "Discrepancy ID must not be null");
        Objects.requireNonNull(settlementPostingId, "Settlement posting ID must not be null");
        Objects.requireNonNull(
                originalLedgerTransactionId,
                "Original Ledger transaction ID must not be null"
        );
        Objects.requireNonNull(kind, "Correction kind must not be null");
        Objects.requireNonNull(requestedBy, "Requester ID must not be null");
        Objects.requireNonNull(reason, "Correction reason must not be null");
        Objects.requireNonNull(requestedAt, "Request time must not be null");
        Objects.requireNonNull(status, "Correction status must not be null");
        Objects.requireNonNull(updatedAt, "Update time must not be null");
    }
}
