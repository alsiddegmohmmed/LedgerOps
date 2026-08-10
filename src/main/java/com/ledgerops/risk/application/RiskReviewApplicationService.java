package com.ledgerops.risk.application;

import com.ledgerops.audit.api.AuditAppendPort;
import com.ledgerops.messaging.api.MessageOutbox;
import com.ledgerops.risk.api.RiskReviewAssignmentRequest;
import com.ledgerops.risk.api.RiskReviewCreationRequest;
import com.ledgerops.risk.api.RiskReviewDecisionRequest;
import com.ledgerops.risk.api.RiskReviewPort;
import com.ledgerops.risk.api.RiskReviewSnapshot;
import com.ledgerops.risk.domain.RiskReview;
import com.ledgerops.risk.domain.RiskReviewStateException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
public class RiskReviewApplicationService implements RiskReviewPort {
    private final RiskReviewStore store;
    private final MessageOutbox outbox;
    private final AuditAppendPort audit;
    private final Clock clock;

    public RiskReviewApplicationService(
            RiskReviewStore store,
            MessageOutbox outbox,
            AuditAppendPort audit,
            Clock clock
    ) {
        this.store = store;
        this.outbox = outbox;
        this.audit = audit;
        this.clock = clock;
    }

    @Override
    @Transactional
    public RiskReviewSnapshot createIfAbsent(RiskReviewCreationRequest request) {
        RiskReview candidate = RiskReview.create(
                com.ledgerops.risk.api.RiskReviewId.newId(),
                request.tenantId(), request.paymentId(), request.merchantId(), request.evaluationId(),
                request.priority(), request.slaVersion(), request.createdAt(), request.dueAt());
        RiskReview stored = store.insertIfAbsent(candidate);
        if (stored.version() == 0 && stored.reviewId().equals(candidate.reviewId())) {
            appendLifecycle(stored, "CREATED", request.createdAt(), request.evaluationId());
        }
        return stored.snapshot();
    }

    @Override
    public Optional<RiskReviewSnapshot> findByTenantAndId(UUID tenantId, UUID reviewId) {
        return store.findByTenantAndId(tenantId, reviewId).map(RiskReview::snapshot);
    }

    @Override
    public Optional<RiskReviewSnapshot> lockByTenantAndId(UUID tenantId, UUID reviewId) {
        return store.lockByTenantAndId(tenantId, reviewId).map(RiskReview::snapshot);
    }

    @Override
    public List<RiskReviewSnapshot> queue(UUID tenantId) {
        return store.queue(tenantId).stream().map(RiskReview::snapshot).toList();
    }

    @Override
    public List<RiskReviewSnapshot> queue(UUID tenantId, Set<UUID> merchantIds) {
        return store.queue(tenantId, Set.copyOf(merchantIds)).stream()
                .map(RiskReview::snapshot).toList();
    }

    @Override
    @Transactional
    public RiskReviewSnapshot assign(RiskReviewAssignmentRequest request) {
        RiskReview current = store.lockByTenantAndId(request.tenantId(), request.reviewId())
                .orElseThrow(() -> new RiskReviewNotFoundException(request.reviewId()));
        RiskReview updated = current.assign(request.assignedAnalystId(), request.priority());
        if (!store.update(updated, current.version())) {
            throw new RiskReviewConcurrencyException(request.reviewId());
        }
        appendLifecycle(updated, "ASSIGNED", clock.instant(), request.correlationId());
        audit.appendAction("application-user", request.actorId().toString(), "HUMAN",
                request.tenantId(), "risk.review.assigned", "risk-review",
                request.reviewId().toString(), request.reason(),
                "{\"assignedAnalystId\":\"" + request.assignedAnalystId() + "\"}",
                request.correlationId().toString());
        return updated.snapshot();
    }

    @Override
    @Transactional
    public RiskReviewSnapshot decide(RiskReviewDecisionRequest request) {
        RiskReview current = store.lockByTenantAndId(request.tenantId(), request.reviewId())
                .orElseThrow(() -> new RiskReviewNotFoundException(request.reviewId()));
        if (!current.paymentId().equals(request.paymentId())) {
            throw new RiskReviewConsistencyException("RiskReview is not linked to the requested Payment");
        }
        if (current.status() == com.ledgerops.risk.api.RiskReviewStatus.DECIDED
                || current.status() == com.ledgerops.risk.api.RiskReviewStatus.ESCALATED) {
            if (current.decision() == request.decision()
                    && java.util.Objects.equals(current.caseId(), request.caseId())
                    && java.util.Objects.equals(current.decisionReason(), request.reason())) {
                return current.snapshot();
            }
            throw new RiskReviewStateException("RiskReview already has a different final decision");
        }
        RiskReview updated = current.decide(
                request.analystId(), request.decision(), request.reason(),
                request.caseId(), clock.instant());
        if (!store.update(updated, current.version())) {
            throw new RiskReviewConcurrencyException(request.reviewId());
        }
        appendLifecycle(updated, updated.status().name(), clock.instant(), request.causationId());
        audit.appendAction("application-user", request.analystId().toString(), "HUMAN",
                request.tenantId(), "risk.review." + request.decision().name().toLowerCase(),
                "risk-review", request.reviewId().toString(), request.reason(),
                "{\"paymentId\":\"" + request.paymentId() + "\",\"caseId\":"
                        + (request.caseId() == null ? "null" : "\"" + request.caseId() + "\"") + "}",
                request.correlationId().toString());
        return updated.snapshot();
    }

    private void appendLifecycle(RiskReview review, String eventType, java.time.Instant occurredAt,
                                 UUID causationId) {
        outbox.appendOrGet(RiskLifecycleEventFactory.draft(review, eventType, occurredAt,
                UUID.nameUUIDFromBytes(("risk-correlation:" + review.reviewId().value()
                        + ":" + review.version()).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                causationId));
    }
}
