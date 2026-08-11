package com.ledgerops.reconciliation.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SettlementPostingStore {

    List<SettlementPostingCandidate> findEligibleCandidates(UUID tenantId, UUID runId);

    PostingWork ensureWork(
            SettlementPostingCandidate candidate,
            String templateVersion,
            String instructionHash,
            Instant createdAt
    );

    Optional<PostingWork> lockWorkForPosting(
            UUID tenantId,
            UUID batchFamilyId,
            UUID settlementPostingId
    );

    boolean paymentSettlementIsPosted(UUID tenantId, UUID paymentId, String templateVersion);

    void markPosted(UUID tenantId, UUID settlementPostingId, UUID ledgerTransactionId, Instant postedAt);

    void recordFailure(
            UUID tenantId,
            UUID settlementPostingId,
            String failureCode,
            String safeMessage,
            Instant failedAt
    );

    record SettlementPostingCandidate(
            UUID tenantId,
            UUID batchFamilyId,
            UUID runId,
            UUID snapshotId,
            UUID canonicalRecordVersionId,
            UUID occurrenceId,
            String subjectType,
            UUID subjectId,
            UUID paymentId,
            BigDecimal amount,
            Currency currency
    ) {
    }

    record PostingWork(
            UUID settlementPostingId,
            UUID tenantId,
            UUID batchFamilyId,
            UUID runId,
            UUID canonicalRecordVersionId,
            UUID occurrenceId,
            String subjectType,
            UUID subjectId,
            UUID paymentId,
            String templateVersion,
            BigDecimal amount,
            Currency currency,
            String instructionHash,
            String applicationStatus,
            UUID ledgerTransactionId
    ) {
    }
}
