package com.ledgerops.payment.application;

import com.ledgerops.payment.api.PaymentCaseResolutionPort;
import com.ledgerops.payment.api.PaymentCaseResolutionRequest;
import com.ledgerops.payment.api.PaymentCaseResolutionResult;
import com.ledgerops.payment.api.RiskPaymentResolution;
import com.ledgerops.payment.domain.Payment;
import com.ledgerops.payment.domain.PaymentId;
import com.ledgerops.payment.domain.PaymentStatus;
import com.ledgerops.risk.api.RiskReviewPort;
import com.ledgerops.risk.api.RiskReviewSnapshot;
import com.ledgerops.risk.api.RiskReviewStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

@Service
public class PaymentCaseResolutionService implements PaymentCaseResolutionPort {
    private final PaymentCompletionStore paymentStore;
    private final PaymentLifecycleStore lifecycleStore;
    private final RiskReviewPort riskReviews;
    private final PaymentLifecycleEventAppender lifecycleEvents;
    private final Clock clock;

    public PaymentCaseResolutionService(
            PaymentCompletionStore paymentStore,
            PaymentLifecycleStore lifecycleStore,
            RiskReviewPort riskReviews,
            PaymentLifecycleEventAppender lifecycleEvents,
            Clock clock
    ) {
        this.paymentStore = paymentStore;
        this.lifecycleStore = lifecycleStore;
        this.riskReviews = riskReviews;
        this.lifecycleEvents = lifecycleEvents;
        this.clock = clock;
    }

    @Override
    @Transactional
    public PaymentCaseResolutionResult applyRiskResolution(PaymentCaseResolutionRequest request) {
        VersionedPayment current = paymentStore.lockByTenantAndId(
                        request.tenantId(), PaymentId.from(request.paymentId()))
                .orElseThrow(() -> new PaymentLifecycleNotFoundException(
                        request.tenantId(), PaymentId.from(request.paymentId())));
        Payment payment = current.payment();
        RiskReviewSnapshot review = riskReviews.lockByTenantAndId(
                        request.tenantId(), request.riskReviewId())
                .orElseThrow(() -> new PaymentRiskReviewNotFoundException(request.riskReviewId()));
        if (review.status() != RiskReviewStatus.ESCALATED
                || !request.caseId().equals(review.caseId())
                || !review.paymentId().equals(request.paymentId())) {
            throw new PaymentCaseResolutionConsistencyException("Case is not the escalated RiskReview for this Payment");
        }
        PaymentStatus target = request.resolution() == RiskPaymentResolution.RISK_APPROVE
                ? PaymentStatus.APPROVED : PaymentStatus.REJECTED;
        if (payment.status() == target) {
            return new PaymentCaseResolutionResult(request.tenantId(), request.paymentId(),
                    target, target, false);
        }
        if (payment.status() != PaymentStatus.RISK_REVIEW) {
            throw new PaymentLifecycleStateException(payment.id(), PaymentStatus.RISK_REVIEW, payment.status());
        }
        Payment updated = target == PaymentStatus.APPROVED ? payment.approve() : payment.reject();
        if (!lifecycleStore.compareAndSet(updated, current.version())) {
            throw new PaymentOptimisticConcurrencyException(payment.id(), current.version());
        }
        lifecycleEvents.append(payment, updated, current.version() + 1, "CASEWORK",
                request.resolution().name(), request.correlationId(), request.causationId(), clock.instant());
        return new PaymentCaseResolutionResult(request.tenantId(), request.paymentId(),
                payment.status(), updated.status(), true);
    }
}
