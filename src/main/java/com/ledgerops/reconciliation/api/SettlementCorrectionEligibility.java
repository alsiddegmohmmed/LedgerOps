package com.ledgerops.reconciliation.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.Objects;
import java.util.UUID;

public record SettlementCorrectionEligibility(
        UUID tenantId,
        UUID batchFamilyId,
        UUID currentRunId,
        UUID invalidatingRunId,
        UUID discrepancyId,
        UUID settlementPostingId,
        UUID originalLedgerTransactionId,
        UUID canonicalRecordVersionId,
        String subjectType,
        UUID subjectId,
        BigDecimal amount,
        Currency currency,
        Instant checkedAt
) {

    public SettlementCorrectionEligibility {
        Objects.requireNonNull(tenantId, "Tenant ID must not be null");
        Objects.requireNonNull(batchFamilyId, "Batch family ID must not be null");
        Objects.requireNonNull(currentRunId, "Current run ID must not be null");
        Objects.requireNonNull(invalidatingRunId, "Invalidating run ID must not be null");
        Objects.requireNonNull(discrepancyId, "Discrepancy ID must not be null");
        Objects.requireNonNull(settlementPostingId, "Settlement posting ID must not be null");
        Objects.requireNonNull(
                originalLedgerTransactionId,
                "Original Ledger transaction ID must not be null"
        );
        Objects.requireNonNull(
                canonicalRecordVersionId,
                "Canonical record version ID must not be null"
        );
        Objects.requireNonNull(subjectType, "Subject type must not be null");
        Objects.requireNonNull(subjectId, "Subject ID must not be null");
        Objects.requireNonNull(amount, "Amount must not be null");
        Objects.requireNonNull(currency, "Currency must not be null");
        Objects.requireNonNull(checkedAt, "Checked time must not be null");
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("Correction amount must be positive");
        }
        if (currentRunId.equals(invalidatingRunId)) {
            throw new IllegalArgumentException(
                    "Invalidating run must differ from the current run"
            );
        }
    }
}
