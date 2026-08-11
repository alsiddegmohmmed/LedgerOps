package com.ledgerops.reconciliation.application;

import com.ledgerops.reconciliation.api.SettlementBatchSnapshot;
import com.ledgerops.reconciliation.api.SettlementValidationItemSnapshot;
import com.ledgerops.reconciliation.domain.SettlementBatchIdentity;
import com.ledgerops.reconciliation.domain.SettlementBatchStatus;
import com.ledgerops.reconciliation.domain.SettlementValidationReasonCode;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface SettlementBatchStore {

    SettlementBatchSnapshot insertReceived(
            UUID batchVersionId,
            SettlementBatchIdentity identity,
            String rawFileSha256,
            String objectKey,
            long byteSize,
            UUID supersedesBatchVersionId,
            UUID createdByApplicationUserId,
            Instant now
    );

    Optional<SettlementBatchSnapshot> findById(UUID tenantId, UUID batchVersionId);

    List<SettlementBatchSnapshot> findByTenant(UUID tenantId);

    List<SettlementValidationItemSnapshot> validationItems(UUID tenantId, UUID batchVersionId);

    void startValidation(UUID tenantId, UUID batchVersionId, Instant now);

    void clearValidation(UUID tenantId, UUID batchVersionId);

    void persistValidationChunk(
            UUID tenantId,
            UUID batchVersionId,
            List<OccurrenceDraft> occurrences,
            List<ValidationItemDraft> validationItems
    );

    void quarantineOccurrence(
            UUID tenantId,
            UUID batchVersionId,
            long rowNumber,
            SettlementValidationReasonCode reasonCode,
            ValidationItemDraft validationItem
    );

    void finishValidation(
            UUID tenantId,
            UUID batchVersionId,
            long totalRows,
            long validRows,
            long invalidRows,
            Instant now
    );

    void failValidation(
            UUID tenantId,
            UUID batchVersionId,
            SettlementValidationReasonCode reasonCode,
            Instant now
    );

    void startProcessing(UUID tenantId, UUID batchVersionId, Instant now);

    void finishProcessing(UUID tenantId, UUID batchVersionId, Instant now);

    void failProcessing(UUID tenantId, UUID batchVersionId, Instant now);

    List<SettlementOccurrenceRow> readValidOccurrences(UUID batchVersionId, int page, int pageSize);

    void persistCanonicalChunk(UUID batchVersionId, List<SettlementOccurrenceRow> rows, Instant now);

    record OccurrenceDraft(
            long rowNumber,
            String providerRecordKey,
            String normalizedContentHash,
            String normalizedContent,
            String validationState,
            String reasonCode,
            UUID occurrenceId,
            UUID tenantId
    ) {
    }

    record ValidationItemDraft(
            long rowNumber,
            String reasonCode,
            String safeEvidence,
            UUID validationItemId,
            Instant createdAt
    ) {
    }

    record SettlementOccurrenceRow(
            UUID occurrenceId,
            UUID batchVersionId,
            UUID tenantId,
            long rowNumber,
            String providerRecordKey,
            String normalizedContentHash,
            String normalizedContent
    ) {
    }
}
