package com.ledgerops.reconciliation.application;

import java.util.Objects;
import java.util.UUID;

public record ReconciliationSnapshotOccurrence(
        UUID snapshotId,
        UUID tenantId,
        UUID batchVersionId,
        UUID occurrenceId,
        UUID canonicalRecordVersionId,
        long rowNumber,
        String providerRecordKey,
        String normalizedContentHash,
        String normalizedContent,
        String validationState,
        String reasonCode
) {

    public ReconciliationSnapshotOccurrence {
        Objects.requireNonNull(snapshotId, "Snapshot ID must not be null");
        Objects.requireNonNull(tenantId, "Tenant ID must not be null");
        Objects.requireNonNull(batchVersionId, "Batch version ID must not be null");
        Objects.requireNonNull(occurrenceId, "Occurrence ID must not be null");
        if (rowNumber <= 0) {
            throw new IllegalArgumentException("Snapshot occurrence row number must be positive");
        }
        Objects.requireNonNull(providerRecordKey, "Provider record key must not be null");
        Objects.requireNonNull(normalizedContent, "Normalized content must not be null");
        Objects.requireNonNull(validationState, "Validation state must not be null");
        if (!validationState.equals("VALID") && !validationState.equals("QUARANTINED")) {
            throw new IllegalArgumentException("Unsupported settlement validation state");
        }
        if (validationState.equals("VALID")
                && (canonicalRecordVersionId == null || normalizedContentHash == null)) {
            throw new IllegalArgumentException("Valid occurrence must have canonical identity and hash");
        }
    }
}
