package com.ledgerops.reconciliation.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;

public record ReconciliationPostingSnapshot(
        UUID settlementPostingId,
        UUID tenantId,
        UUID runId,
        UUID canonicalRecordVersionId,
        UUID occurrenceId,
        String subjectType,
        UUID subjectId,
        String templateVersion,
        BigDecimal amount,
        Currency currency,
        String instructionHash,
        String applicationStatus,
        UUID ledgerTransactionId,
        Instant createdAt,
        Instant postedAt
) {
}
