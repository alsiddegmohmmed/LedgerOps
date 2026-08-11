package com.ledgerops.reconciliation.api;

import com.ledgerops.reconciliation.domain.ReconciliationStatus;

import java.time.Instant;
import java.util.UUID;

public record ReconciliationStatusHistorySnapshot(
        UUID statusId,
        UUID tenantId,
        String subjectType,
        UUID subjectId,
        UUID runId,
        ReconciliationStatus status,
        Instant occurredAt
) {
}
