package com.ledgerops.reconciliation.application;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface ReconciliationSnapshotStore {

    SnapshotIdentity createBuildingSnapshot(
            UUID tenantId,
            UUID batchFamilyId,
            UUID batchVersionId,
            String rulesVersion,
            Instant sourceCutoff,
            Instant now
    );

    void insertSettlementRecords(
            UUID snapshotId,
            UUID tenantId,
            UUID batchVersionId,
            List<ReconciliationSnapshotOccurrence> occurrences,
            Instant capturedAt
    );

    void insertFinancialSubjects(
            UUID snapshotId,
            UUID tenantId,
            UUID batchVersionId,
            List<ReconciliationSnapshotSubject> subjects,
            Instant capturedAt
    );

    void completeSnapshot(
            UUID snapshotId,
            String snapshotSha256,
            long recordCount,
            long factCount,
            Instant completedAt
    );

    void failSnapshot(UUID snapshotId, String reason, Instant failedAt);

    UUID createQueuedRun(SnapshotIdentity snapshot, Instant createdAt);

    void startRun(UUID runId, Instant startedAt);

    void seedPendingStatuses(UUID tenantId, UUID runId, UUID snapshotId, Instant occurredAt);

    void seedAwaitingBatchStatuses(UUID tenantId, UUID runId, UUID snapshotId, Instant occurredAt);

    void insertResults(
            UUID runId,
            UUID tenantId,
            List<ReconciliationResultDraft> results,
            Instant createdAt
    );

    void appendSubjectStatuses(
            UUID tenantId,
            UUID runId,
            List<SubjectStatusDraft> statuses,
            Instant occurredAt
    );

    void completeRun(UUID runId, long matched, long unmatched, long discrepancies, Instant completedAt);

    void failRun(UUID runId, String reason, Instant failedAt);

    void promoteCurrentRun(UUID tenantId, UUID batchFamilyId, UUID runId, Instant promotedAt);

    java.util.Optional<CurrentRun> findCurrentRun(UUID tenantId, UUID batchFamilyId);

    List<ReconciliationSnapshotOccurrence> readOccurrences(
            UUID snapshotId,
            int page,
            int pageSize
    );

    Map<SubjectKey, ReconciliationSnapshotSubjectRow> findSubjects(
            UUID snapshotId,
            Collection<SubjectKey> keys
    );

    Map<String, List<SubjectKey>> findSubjectKeysByProviderReferences(
            UUID snapshotId,
            Collection<String> providerReferences
    );

    List<ReconciliationSnapshotSubjectRow> findSubjectsWithoutSettlementRecord(
            UUID snapshotId,
            int page,
            int pageSize
    );

    record CurrentRun(UUID tenantId, UUID batchFamilyId, UUID runId, Instant promotedAt) {
    }

    record SnapshotIdentity(
            UUID snapshotId,
            UUID tenantId,
            UUID batchFamilyId,
            UUID batchVersionId,
            int runNumber,
            String rulesVersion,
            Instant sourceCutoff
    ) {
    }

    record SubjectKey(String subjectType, UUID subjectId) {
    }

    record SubjectStatusDraft(
            String subjectType,
            UUID subjectId,
            String status
    ) {
    }

    record ReconciliationSnapshotSubjectRow(
            SubjectKey key,
            UUID paymentId,
            UUID merchantId,
            java.math.BigDecimal amount,
            java.util.Currency currency,
            String providerId,
            String providerIdempotencyKey,
            UUID providerEvidenceId,
            UUID providerResultId,
            String providerReference,
            String providerResultCategory,
            Instant providerObservedAt,
            String financialStatus,
            Instant appliedAt,
            UUID ledgerTransactionId,
            Instant ledgerPostedAt,
            UUID ledgerCompensatesTransactionId,
            java.math.BigDecimal ledgerTotalDebits,
            java.math.BigDecimal ledgerTotalCredits,
            String ledgerEntriesJson
    ) {
    }
}
