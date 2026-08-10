package com.ledgerops.risk.domain;

import com.ledgerops.risk.api.RiskReviewDecision;
import com.ledgerops.risk.api.RiskReviewId;
import com.ledgerops.risk.api.RiskReviewSnapshot;
import com.ledgerops.risk.api.RiskReviewStatus;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public final class RiskReview {

    private final RiskReviewId reviewId;
    private final UUID tenantId;
    private final UUID paymentId;
    private final UUID merchantId;
    private final UUID evaluationId;
    private final RiskReviewStatus status;
    private final UUID assignedAnalystId;
    private final int priority;
    private final int slaVersion;
    private final Instant createdAt;
    private final Instant dueAt;
    private final RiskReviewDecision decision;
    private final String decisionReason;
    private final UUID caseId;
    private final Instant decidedAt;
    private final long version;

    private RiskReview(
            RiskReviewId reviewId,
            UUID tenantId,
            UUID paymentId,
            UUID merchantId,
            UUID evaluationId,
            RiskReviewStatus status,
            UUID assignedAnalystId,
            int priority,
            int slaVersion,
            Instant createdAt,
            Instant dueAt,
            RiskReviewDecision decision,
            String decisionReason,
            UUID caseId,
            Instant decidedAt,
            long version
    ) {
        this.reviewId = Objects.requireNonNull(reviewId, "Risk review ID must not be null");
        this.tenantId = Objects.requireNonNull(tenantId, "Tenant ID must not be null");
        this.paymentId = Objects.requireNonNull(paymentId, "Payment ID must not be null");
        this.merchantId = merchantId;
        this.evaluationId = Objects.requireNonNull(evaluationId, "Evaluation ID must not be null");
        this.status = Objects.requireNonNull(status, "Risk review status must not be null");
        this.assignedAnalystId = assignedAnalystId;
        this.priority = requirePriority(priority);
        if (slaVersion < 1) throw new IllegalArgumentException("Risk review SLA version must be positive");
        this.slaVersion = slaVersion;
        this.createdAt = Objects.requireNonNull(createdAt, "Creation time must not be null");
        this.dueAt = Objects.requireNonNull(dueAt, "Due time must not be null");
        this.decision = decision;
        this.decisionReason = decisionReason;
        this.caseId = caseId;
        this.decidedAt = decidedAt;
        if (version < 0) {
            throw new IllegalArgumentException("Risk review version must not be negative");
        }
        this.version = version;
        if (dueAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("Risk review due time must not precede creation");
        }
        validateState();
    }

    public static RiskReview create(
            RiskReviewId reviewId,
            UUID tenantId,
            UUID paymentId,
            UUID evaluationId,
            int priority,
            int slaVersion,
            Instant createdAt,
            Instant dueAt
    ) {
        return create(reviewId, tenantId, paymentId, null, evaluationId, priority,
                slaVersion, createdAt, dueAt);
    }

    public static RiskReview create(
            RiskReviewId reviewId,
            UUID tenantId,
            UUID paymentId,
            UUID merchantId,
            UUID evaluationId,
            int priority,
            int slaVersion,
            Instant createdAt,
            Instant dueAt
    ) {
        return new RiskReview(reviewId, tenantId, paymentId, merchantId, evaluationId,
                RiskReviewStatus.UNASSIGNED, null, priority, slaVersion, createdAt, dueAt,
                null, null, null, null, 0);
    }

    public static RiskReview restore(
            RiskReviewId reviewId,
            UUID tenantId,
            UUID paymentId,
            UUID merchantId,
            UUID evaluationId,
            RiskReviewStatus status,
            UUID assignedAnalystId,
            int priority,
            int slaVersion,
            Instant createdAt,
            Instant dueAt,
            RiskReviewDecision decision,
            String decisionReason,
            UUID caseId,
            Instant decidedAt,
            long version
    ) {
        return new RiskReview(reviewId, tenantId, paymentId, merchantId, evaluationId, status,
                assignedAnalystId, priority, slaVersion, createdAt, dueAt, decision,
                decisionReason, caseId, decidedAt, version);
    }

    public RiskReview assign(UUID analystId, int newPriority) {
        Objects.requireNonNull(analystId, "Assigned analyst ID must not be null");
        if (status != RiskReviewStatus.UNASSIGNED && status != RiskReviewStatus.ASSIGNED) {
            throw new RiskReviewStateException("Only an open RiskReview may be assigned");
        }
        return copy(RiskReviewStatus.ASSIGNED, analystId, newPriority,
                decision, decisionReason, caseId, decidedAt);
    }

    public RiskReview decide(
            UUID analystId,
            RiskReviewDecision nextDecision,
            String reason,
            UUID nextCaseId,
            Instant decidedAt
    ) {
        Objects.requireNonNull(analystId, "Analyst ID must not be null");
        Objects.requireNonNull(nextDecision, "Risk review decision must not be null");
        Objects.requireNonNull(decidedAt, "Decision time must not be null");
        if (status != RiskReviewStatus.ASSIGNED) {
            throw new RiskReviewStateException("Only an assigned RiskReview may be decided");
        }
        if (!analystId.equals(assignedAnalystId)) {
            throw new RiskReviewAuthorizationException("Only the assigned analyst may decide");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Risk decision reason must not be blank");
        }
        if (nextDecision == RiskReviewDecision.ESCALATE && nextCaseId == null) {
            throw new IllegalArgumentException("Escalation requires a stable case ID");
        }
        if (nextDecision != RiskReviewDecision.ESCALATE && nextCaseId != null) {
            throw new IllegalArgumentException("Only escalation may carry a case ID");
        }
        RiskReviewStatus nextStatus = nextDecision == RiskReviewDecision.ESCALATE
                ? RiskReviewStatus.ESCALATED : RiskReviewStatus.DECIDED;
        return copy(nextStatus, assignedAnalystId, priority, nextDecision, reason,
                nextCaseId, decidedAt);
    }

    public RiskReviewSnapshot snapshot() {
        return new RiskReviewSnapshot(reviewId.value(), tenantId, paymentId, merchantId, evaluationId,
                status, assignedAnalystId, priority, slaVersion, createdAt, dueAt, decision,
                decisionReason, caseId, decidedAt, version);
    }

    public RiskReviewId reviewId() { return reviewId; }
    public UUID tenantId() { return tenantId; }
    public UUID paymentId() { return paymentId; }
    public UUID merchantId() { return merchantId; }
    public UUID evaluationId() { return evaluationId; }
    public RiskReviewStatus status() { return status; }
    public UUID assignedAnalystId() { return assignedAnalystId; }
    public int priority() { return priority; }
    public int slaVersion() { return slaVersion; }
    public Instant createdAt() { return createdAt; }
    public Instant dueAt() { return dueAt; }
    public RiskReviewDecision decision() { return decision; }
    public String decisionReason() { return decisionReason; }
    public UUID caseId() { return caseId; }
    public Instant decidedAt() { return decidedAt; }
    public long version() { return version; }

    private RiskReview copy(
            RiskReviewStatus nextStatus,
            UUID nextAnalyst,
            int nextPriority,
            RiskReviewDecision nextDecision,
            String nextReason,
            UUID nextCaseId,
            Instant nextDecidedAt
    ) {
        return new RiskReview(reviewId, tenantId, paymentId, merchantId, evaluationId, nextStatus,
                nextAnalyst, nextPriority, slaVersion, createdAt, dueAt, nextDecision, nextReason,
                nextCaseId, nextDecidedAt, Math.addExact(version, 1));
    }

    private void validateState() {
        boolean finalState = status == RiskReviewStatus.DECIDED
                || status == RiskReviewStatus.ESCALATED;
        if (status == RiskReviewStatus.UNASSIGNED && assignedAnalystId != null) {
            throw new IllegalArgumentException("Unassigned RiskReview cannot have an analyst");
        }
        if ((status == RiskReviewStatus.ASSIGNED || finalState) && assignedAnalystId == null) {
            throw new IllegalArgumentException("Assigned or decided RiskReview requires an analyst");
        }
        if (finalState && (decision == null || decisionReason == null || decidedAt == null)) {
            throw new IllegalArgumentException("Final RiskReview requires decision evidence");
        }
        if (status == RiskReviewStatus.ESCALATED && caseId == null) {
            throw new IllegalArgumentException("Escalated RiskReview requires a Case ID");
        }
        if (status == RiskReviewStatus.DECIDED && caseId != null) {
            throw new IllegalArgumentException("Decided RiskReview cannot have a Case ID");
        }
    }

    private static int requirePriority(int value) {
        if (value < 0) throw new IllegalArgumentException("Risk review priority must not be negative");
        return value;
    }
}
