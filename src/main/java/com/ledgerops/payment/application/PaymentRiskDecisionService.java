package com.ledgerops.payment.application;

import com.ledgerops.payment.domain.Payment;
import com.ledgerops.payment.domain.PaymentId;
import com.ledgerops.payment.domain.PaymentStatus;
import com.ledgerops.risk.api.RiskDecision;
import com.ledgerops.risk.api.RiskEvaluationRequest;
import com.ledgerops.risk.api.RiskEvaluationResult;
import com.ledgerops.risk.api.RiskEvaluationUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

@Service
public class PaymentRiskDecisionService {

    private static final Logger LOGGER = LoggerFactory.getLogger(
            PaymentRiskDecisionService.class
    );

    private final PaymentLifecycleStore lifecycleStore;
    private final RiskEvaluationUseCase riskEvaluationUseCase;

    public PaymentRiskDecisionService(
            PaymentLifecycleStore lifecycleStore,
            RiskEvaluationUseCase riskEvaluationUseCase
    ) {
        this.lifecycleStore = lifecycleStore;
        this.riskEvaluationUseCase = riskEvaluationUseCase;
    }

    @Transactional
    public PaymentRiskDecisionResult evaluate(UUID tenantId, PaymentId paymentId) {
        Objects.requireNonNull(tenantId, "Tenant ID must not be null");
        Objects.requireNonNull(paymentId, "Payment ID must not be null");

        VersionedPayment current = lifecycleStore.findByTenantAndId(tenantId, paymentId)
                .orElseThrow(() -> new PaymentLifecycleNotFoundException(
                        tenantId,
                        paymentId
                ));
        Payment payment = current.payment();

        if (payment.status() != PaymentStatus.VALIDATING) {
            throw new PaymentLifecycleStateException(
                    payment.id(),
                    PaymentStatus.VALIDATING,
                    payment.status()
            );
        }

        RiskEvaluationResult riskResult = riskEvaluationUseCase.evaluate(
                new RiskEvaluationRequest(
                        payment.tenantId(),
                        payment.id().value(),
                        payment.amount().amount(),
                        payment.amount().currency()
                )
        );
        Payment decided = applyDecision(payment, riskResult.decision());

        if (!lifecycleStore.compareAndSet(decided, current.version())) {
            throw new PaymentOptimisticConcurrencyException(
                    payment.id(),
                    current.version()
            );
        }

        PaymentRiskDecisionResult result = new PaymentRiskDecisionResult(
                new VersionedPayment(decided, Math.addExact(current.version(), 1)),
                riskResult
        );
        LOGGER.info(
                "Payment risk decision applied tenantId={} paymentId={} decision={} status={} evaluationId={} profileId={} profileVersion={} finalScore={}",
                payment.tenantId(),
                payment.id().value(),
                riskResult.decision(),
                decided.status(),
                riskResult.evaluationId(),
                riskResult.profileId(),
                riskResult.profileVersion(),
                riskResult.finalScore()
        );
        return result;
    }

    private Payment applyDecision(Payment payment, RiskDecision decision) {
        return switch (decision) {
            case APPROVE -> payment.approve();
            case MANUAL_REVIEW -> payment.requestRiskReview();
            case REJECT -> payment.reject();
        };
    }
}
