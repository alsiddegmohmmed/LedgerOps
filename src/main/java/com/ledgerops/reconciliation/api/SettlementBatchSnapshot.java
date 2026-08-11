package com.ledgerops.reconciliation.api;

import com.ledgerops.reconciliation.domain.SettlementBatchStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record SettlementBatchSnapshot(
        UUID batchVersionId,
        UUID familyId,
        UUID tenantId,
        String providerId,
        String providerBatchReference,
        LocalDate settlementPeriodStart,
        LocalDate settlementPeriodEnd,
        String rawFileSha256,
        String objectKey,
        long byteSize,
        SettlementBatchStatus status,
        UUID supersedesBatchVersionId,
        long totalRows,
        long validRows,
        long invalidRows,
        String structuralErrorCode,
        Instant createdAt,
        Instant updatedAt
) {
}
