package com.ledgerops.reconciliation.application;

import com.ledgerops.reconciliation.api.ReconciliationCurrentRunSnapshot;
import com.ledgerops.reconciliation.api.ReconciliationPostingSnapshot;
import com.ledgerops.reconciliation.api.ReconciliationResultSnapshot;
import com.ledgerops.reconciliation.api.ReconciliationRunSnapshot;
import com.ledgerops.reconciliation.api.ReconciliationStatusHistorySnapshot;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReconciliationRunQuery {

    List<ReconciliationRunSnapshot> findRuns(
            UUID tenantId,
            Optional<UUID> batchFamilyId,
            int limit
    );

    Optional<ReconciliationRunSnapshot> findRun(UUID tenantId, UUID runId);

    List<ReconciliationResultSnapshot> findResults(
            UUID tenantId,
            UUID runId,
            Optional<String> resultStatus,
            Optional<String> discrepancyCategory,
            int limit,
            int offset
    );

    Optional<ReconciliationCurrentRunSnapshot> findCurrent(
            UUID tenantId,
            UUID batchFamilyId
    );

    List<ReconciliationStatusHistorySnapshot> findStatusHistory(
            UUID tenantId,
            String subjectType,
            UUID subjectId
    );

    List<ReconciliationPostingSnapshot> findPostings(
            UUID tenantId,
            UUID runId,
            int limit,
            int offset
    );
}
