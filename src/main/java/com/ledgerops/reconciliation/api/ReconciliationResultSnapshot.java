package com.ledgerops.reconciliation.api;

import com.ledgerops.reconciliation.domain.ReconciliationDiscrepancyCategory;

import java.time.Instant;
import java.util.UUID;

public record ReconciliationResultSnapshot(
        UUID resultId,
        UUID occurrenceId,
        UUID canonicalRecordVersionId,
        String subjectType,
        UUID subjectId,
        String resultStatus,
        ReconciliationDiscrepancyCategory discrepancyCategory,
        String providerValuesJson,
        String internalValuesJson,
        Instant createdAt
) {
}
