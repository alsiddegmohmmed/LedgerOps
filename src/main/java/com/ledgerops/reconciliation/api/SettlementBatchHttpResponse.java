package com.ledgerops.reconciliation.api;

import com.ledgerops.reconciliation.domain.SettlementBatchStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

record SettlementBatchHttpResponse(
        UUID batchVersionId,
        UUID familyId,
        UUID tenantId,
        String providerId,
        String providerBatchReference,
        LocalDate settlementPeriodStart,
        LocalDate settlementPeriodEnd,
        String rawFileSha256,
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

    static SettlementBatchHttpResponse from(SettlementBatchSnapshot value) {
        return new SettlementBatchHttpResponse(
                value.batchVersionId(),
                value.familyId(),
                value.tenantId(),
                value.providerId(),
                value.providerBatchReference(),
                value.settlementPeriodStart(),
                value.settlementPeriodEnd(),
                value.rawFileSha256(),
                value.byteSize(),
                value.status(),
                value.supersedesBatchVersionId(),
                value.totalRows(),
                value.validRows(),
                value.invalidRows(),
                value.structuralErrorCode(),
                value.createdAt(),
                value.updatedAt());
    }
}
