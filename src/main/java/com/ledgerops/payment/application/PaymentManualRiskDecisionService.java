package com.ledgerops.payment.application;

import com.ledgerops.messaging.api.MessageOutbox;
import com.ledgerops.payment.api.PaymentManualRiskDecision;
import com.ledgerops.payment.api.PaymentManualRiskDecisionRequest;
import com.ledgerops.payment.api.PaymentManualRiskDecisionResult;
import com.ledgerops.payment.domain.Payment;
import com.ledgerops.payment.domain.PaymentId;
import com.ledgerops.payment.domain.PaymentStatus;
import com.ledgerops.risk.api.RiskReviewDecision;
import com.ledgerops.risk.api.RiskReviewDecisionRequest;
import com.ledgerops.risk.api.RiskReviewPort;
import com.ledgerops.risk.api.RiskReviewSnapshot;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.UUID;

@Service
public class PaymentManualRiskDecisionService {
    private final PaymentCompletionStore paymentStore;
    private final PaymentLifecycleStore lifecycleStore;
    private final RiskReviewPort riskReviews;
    private final PaymentLifecycleEventAppender lifecycleEvents;
    private final MessageOutbox outbox;
    private final Clock clock;

    public PaymentManualRiskDecisionService(
            PaymentCompletionStore paymentStore,
            PaymentLifecycleStore lifecycleStore,
            RiskReviewPort riskReviews,
            PaymentLifecycleEventAppender lifecycleEvents,
            MessageOutbox outbox,
            Clock clock
    ) {
        this.paymentStore = paymentStore;
        this.lifecycleStore = lifecycleStore;
        this.riskReviews = riskReviews;
        this.lifecycleEvents = lifecycleEvents;
        this.outbox = outbox;
        this.clock = clock;
    }

    @Transactional
    public PaymentManualRiskDecisionResult decide(PaymentManualRiskDecisionRequest request) {
        RiskReviewSnapshot beforeReview = riskReviews.findByTenantAndId(
                        request.tenantId(), request.reviewId())
                .orElseThrow(() -> new PaymentRiskReviewNotFoundException(request.reviewId()));
        PaymentId paymentId = PaymentId.from(beforeReview.paymentId());
        VersionedPayment current = paymentStore.lockByTenantAndId(request.tenantId(), paymentId)
                .orElseThrow(() -> new PaymentLifecycleNotFoundException(request.tenantId(), paymentId));
        Payment payment = current.payment();
        PaymentStatus expectedFinalStatus = switch (request.decision()) {
            case APPROVE -> PaymentStatus.APPROVED;
            case REJECT -> PaymentStatus.REJECTED;
            case ESCALATE -> null;
        };
        if (expectedFinalStatus != null && payment.status() == expectedFinalStatus) {
            if (beforeReview.status() == com.ledgerops.risk.api.RiskReviewStatus.DECIDED
                    && beforeReview.decision() == toRiskDecision(request.decision())
                    && request.reason().equals(beforeReview.decisionReason())
                    && request.analystId().equals(beforeReview.assignedAnalystId())) {
                return new PaymentManualRiskDecisionResult(beforeReview, payment.status(), false);
            }
            throw new PaymentRiskDecisionConsistencyException(
                    "Payment already has a final status without the requested Risk decision evidence");
        }
        if (payment.status() != PaymentStatus.RISK_REVIEW) {
            throw new PaymentLifecycleStateException(payment.id(), PaymentStatus.RISK_REVIEW, payment.status());
        }

        UUID caseId = request.decision() == PaymentManualRiskDecision.ESCALATE
                ? stableCaseId(request.reviewId()) : null;
        RiskReviewSnapshot review = riskReviews.decide(new RiskReviewDecisionRequest(
                request.tenantId(), request.reviewId(), payment.id().value(), request.analystId(),
                toRiskDecision(request.decision()), request.reason(), caseId,
                request.correlationId(), request.causationId()));

        if (request.decision() == PaymentManualRiskDecision.ESCALATE) {
            outbox.appendOrGet(PaymentCaseCommandFactory.createRiskReviewCase(
                    payment, review, request.correlationId(), request.causationId(), clock.instant()));
            return new PaymentManualRiskDecisionResult(review, payment.status(), false);
        }

        Payment updated = request.decision() == PaymentManualRiskDecision.APPROVE
                ? payment.approve() : payment.reject();
        if (!lifecycleStore.compareAndSet(updated, current.version())) {
            throw new PaymentOptimisticConcurrencyException(payment.id(), current.version());
        }
        lifecycleEvents.append(payment, updated, current.version() + 1, "HUMAN",
                "RISK_DECISION_" + request.decision().name(), request.correlationId(),
                request.causationId(), clock.instant());
        return new PaymentManualRiskDecisionResult(review, updated.status(), true);
    }

    private RiskReviewDecision toRiskDecision(PaymentManualRiskDecision decision) {
        return switch (decision) {
            case APPROVE -> RiskReviewDecision.APPROVE;
            case REJECT -> RiskReviewDecision.REJECT;
            case ESCALATE -> RiskReviewDecision.ESCALATE;
        };
    }

    static UUID stableCaseId(UUID reviewId) {
        return UUID.nameUUIDFromBytes(("case:RISK_REVIEW:" + reviewId)
                .getBytes(StandardCharsets.UTF_8));
    }
}
