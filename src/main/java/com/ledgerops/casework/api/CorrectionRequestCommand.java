package com.ledgerops.casework.api;

import java.util.Objects;
import java.util.UUID;

public record CorrectionRequestCommand(
        UUID tenantId,
        UUID caseId,
        UUID discrepancyId,
        UUID settlementPostingId,
        UUID originalLedgerTransactionId,
        UUID actorId,
        String reason,
        UUID correlationId,
        boolean confirmation
) {

    public CorrectionRequestCommand {
        Objects.requireNonNull(tenantId, "Tenant ID must not be null");
        Objects.requireNonNull(caseId, "Case ID must not be null");
        Objects.requireNonNull(discrepancyId, "Discrepancy ID must not be null");
        Objects.requireNonNull(settlementPostingId, "Settlement posting ID must not be null");
        Objects.requireNonNull(
                originalLedgerTransactionId,
                "Original Ledger transaction ID must not be null"
        );
        Objects.requireNonNull(actorId, "Actor ID must not be null");
        Objects.requireNonNull(correlationId, "Correlation ID must not be null");
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Correction reason must not be blank");
        }
        if (!confirmation) {
            throw new IllegalArgumentException("Correction request requires explicit confirmation");
        }
    }
}
