package com.ledgerops.casework.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Casework-owned request to exact-compensate one invalidated settlement
 * adjustment. Eligibility and Ledger posting are enforced by the application
 * workflow; this object owns only the durable correction lifecycle.
 */
public final class CorrectionRequest {
    private final UUID correctionId;
    private final UUID tenantId;
    private final UUID caseId;
    private final UUID discrepancyId;
    private final UUID settlementPostingInstructionId;
    private final UUID originalLedgerTransactionId;
    private final CorrectionRequestKind kind;
    private final UUID requestedBy;
    private final String reason;
    private final Instant requestedAt;
    private final CorrectionRequestStatus status;
    private final Instant updatedAt;
    private final UUID compensationLedgerTransactionId;
    private final String failureReason;

    private CorrectionRequest(
            UUID correctionId,
            UUID tenantId,
            UUID caseId,
            UUID discrepancyId,
            UUID settlementPostingInstructionId,
            UUID originalLedgerTransactionId,
            CorrectionRequestKind kind,
            UUID requestedBy,
            String reason,
            Instant requestedAt,
            CorrectionRequestStatus status,
            Instant updatedAt,
            UUID compensationLedgerTransactionId,
            String failureReason
    ) {
        this.correctionId = requireId(correctionId, "Correction ID");
        this.tenantId = requireId(tenantId, "Tenant ID");
        this.caseId = requireId(caseId, "Case ID");
        this.discrepancyId = requireId(discrepancyId, "Discrepancy ID");
        this.settlementPostingInstructionId = requireId(
                settlementPostingInstructionId, "Settlement posting instruction ID");
        this.originalLedgerTransactionId = requireId(
                originalLedgerTransactionId, "Original Ledger transaction ID");
        this.kind = Objects.requireNonNull(kind, "Correction kind must not be null");
        this.requestedBy = requireId(requestedBy, "Correction requester ID");
        this.reason = requireText(reason, "Correction reason");
        this.requestedAt = Objects.requireNonNull(requestedAt, "Correction request time must not be null");
        this.status = Objects.requireNonNull(status, "Correction status must not be null");
        this.updatedAt = Objects.requireNonNull(updatedAt, "Correction update time must not be null");
        if (updatedAt.isBefore(requestedAt)) {
            throw new IllegalArgumentException("Correction update time must not precede request time");
        }
        if (status == CorrectionRequestStatus.COMPLETED
                && compensationLedgerTransactionId == null) {
            throw new IllegalArgumentException(
                    "Completed correction must reference its compensation Ledger transaction");
        }
        if (status != CorrectionRequestStatus.COMPLETED
                && compensationLedgerTransactionId != null) {
            throw new IllegalArgumentException(
                    "Only a completed correction may reference a compensation Ledger transaction");
        }
        if (status == CorrectionRequestStatus.FAILED) {
            this.failureReason = requireText(failureReason, "Correction failure reason");
        } else if (failureReason != null && !failureReason.isBlank()) {
            throw new IllegalArgumentException("Only a failed correction may have a failure reason");
        } else {
            this.failureReason = null;
        }
        this.compensationLedgerTransactionId = compensationLedgerTransactionId;
    }

    public static CorrectionRequest request(
            UUID correctionId,
            UUID tenantId,
            UUID caseId,
            UUID discrepancyId,
            UUID settlementPostingInstructionId,
            UUID originalLedgerTransactionId,
            UUID requestedBy,
            String reason,
            Instant requestedAt
    ) {
        return new CorrectionRequest(
                correctionId,
                tenantId,
                caseId,
                discrepancyId,
                settlementPostingInstructionId,
                originalLedgerTransactionId,
                CorrectionRequestKind.COMPENSATE_SETTLEMENT_ADJUSTMENT,
                requestedBy,
                reason,
                requestedAt,
                CorrectionRequestStatus.REQUESTED,
                requestedAt,
                null,
                null
        );
    }

    public static CorrectionRequest restore(
            UUID correctionId,
            UUID tenantId,
            UUID caseId,
            UUID discrepancyId,
            UUID settlementPostingInstructionId,
            UUID originalLedgerTransactionId,
            CorrectionRequestKind kind,
            UUID requestedBy,
            String reason,
            Instant requestedAt,
            CorrectionRequestStatus status,
            Instant updatedAt,
            UUID compensationLedgerTransactionId,
            String failureReason
    ) {
        return new CorrectionRequest(
                correctionId,
                tenantId,
                caseId,
                discrepancyId,
                settlementPostingInstructionId,
                originalLedgerTransactionId,
                kind,
                requestedBy,
                reason,
                requestedAt,
                status,
                updatedAt,
                compensationLedgerTransactionId,
                failureReason
        );
    }

    public CorrectionRequest beginProcessing(Instant now) {
        requireTransition(CorrectionRequestStatus.PROCESSING);
        return copy(CorrectionRequestStatus.PROCESSING, now, null, null);
    }

    public CorrectionRequest complete(UUID compensationTransactionId, Instant now) {
        requireTransition(CorrectionRequestStatus.COMPLETED);
        requireId(compensationTransactionId, "Compensation Ledger transaction ID");
        return copy(CorrectionRequestStatus.COMPLETED, now, compensationTransactionId, null);
    }

    public CorrectionRequest fail(String failure, Instant now) {
        requireTransition(CorrectionRequestStatus.FAILED);
        requireText(failure, "Correction failure reason");
        return copy(CorrectionRequestStatus.FAILED, now, null, failure);
    }

    public UUID correctionId() { return correctionId; }
    public UUID tenantId() { return tenantId; }
    public UUID caseId() { return caseId; }
    public UUID discrepancyId() { return discrepancyId; }
    public UUID settlementPostingInstructionId() { return settlementPostingInstructionId; }
    public UUID originalLedgerTransactionId() { return originalLedgerTransactionId; }
    public CorrectionRequestKind kind() { return kind; }
    public UUID requestedBy() { return requestedBy; }
    public String reason() { return reason; }
    public Instant requestedAt() { return requestedAt; }
    public CorrectionRequestStatus status() { return status; }
    public Instant updatedAt() { return updatedAt; }
    public UUID compensationLedgerTransactionId() { return compensationLedgerTransactionId; }
    public String failureReason() { return failureReason; }

    private CorrectionRequest copy(
            CorrectionRequestStatus nextStatus,
            Instant now,
            UUID compensationTransactionId,
            String nextFailureReason
    ) {
        return new CorrectionRequest(
                correctionId,
                tenantId,
                caseId,
                discrepancyId,
                settlementPostingInstructionId,
                originalLedgerTransactionId,
                kind,
                requestedBy,
                reason,
                requestedAt,
                nextStatus,
                Objects.requireNonNull(now, "Correction update time must not be null"),
                compensationTransactionId,
                nextFailureReason
        );
    }

    private void requireTransition(CorrectionRequestStatus target) {
        boolean allowed = switch (status) {
            case REQUESTED -> target == CorrectionRequestStatus.PROCESSING;
            case PROCESSING -> target == CorrectionRequestStatus.COMPLETED
                    || target == CorrectionRequestStatus.FAILED;
            case FAILED -> target == CorrectionRequestStatus.PROCESSING;
            case COMPLETED -> false;
        };
        if (!allowed) {
            throw new CorrectionRequestStateException(
                    "Correction cannot transition from " + status + " to " + target);
        }
    }

    private static UUID requireId(UUID value, String label) {
        return Objects.requireNonNull(value, label + " must not be null");
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return value;
    }
}
