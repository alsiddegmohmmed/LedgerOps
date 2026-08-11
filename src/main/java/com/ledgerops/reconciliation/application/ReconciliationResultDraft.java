package com.ledgerops.reconciliation.application;

import com.ledgerops.reconciliation.domain.ReconciliationDiscrepancyCategory;

import java.util.Map;
import java.util.UUID;

public record ReconciliationResultDraft(
        UUID resultId,
        UUID occurrenceId,
        UUID canonicalRecordVersionId,
        String subjectType,
        UUID subjectId,
        UUID relatedPaymentId,
        String resultStatus,
        ReconciliationDiscrepancyCategory discrepancyCategory,
        Map<String, Object> providerValues,
        Map<String, Object> internalValues
) {
}
