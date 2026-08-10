package com.ledgerops.risk.application;

import com.ledgerops.risk.api.RiskConfigurationException;
import com.ledgerops.risk.api.RiskEvaluationRequest;
import com.ledgerops.risk.api.RiskEvaluationResult;
import com.ledgerops.risk.api.RiskEvaluationUseCase;
import com.ledgerops.risk.api.RiskReviewCreationRequest;
import com.ledgerops.risk.api.RiskReviewPort;
import com.ledgerops.risk.api.RiskProcessingError;
import com.ledgerops.risk.api.RiskProcessingException;
import com.ledgerops.risk.domain.RiskEvaluation;
import com.ledgerops.risk.domain.RiskEvaluationId;
import com.ledgerops.risk.domain.RiskEvaluator;
import com.ledgerops.risk.domain.RiskProfile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Objects;

@Service
public class RiskEvaluationService implements RiskEvaluationUseCase {

    private final RiskProfileStore profileStore;
    private final RiskEvaluationStore evaluationStore;
    private final RiskEvaluator evaluator;
    private final Clock clock;
    private final RiskReviewPort riskReviewPort;
    private final RiskReviewSlaPolicy riskReviewSlaPolicy;

    public RiskEvaluationService(
            RiskProfileStore profileStore,
            RiskEvaluationStore evaluationStore,
            RiskEvaluator evaluator,
            Clock clock
    ) {
        this(profileStore, evaluationStore, evaluator, clock, null, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public RiskEvaluationService(
            RiskProfileStore profileStore,
            RiskEvaluationStore evaluationStore,
            RiskEvaluator evaluator,
            Clock clock,
            RiskReviewPort riskReviewPort,
            RiskReviewSlaPolicy riskReviewSlaPolicy
    ) {
        this.profileStore = profileStore;
        this.evaluationStore = evaluationStore;
        this.evaluator = evaluator;
        this.clock = clock;
        this.riskReviewPort = riskReviewPort;
        this.riskReviewSlaPolicy = riskReviewSlaPolicy;
    }

    @Override
    @Transactional
    public RiskEvaluationResult evaluate(RiskEvaluationRequest request) {
        Objects.requireNonNull(request, "Risk evaluation request must not be null");

        try {
            return evaluationStore.findByTenantAndPayment(
                            request.tenantId(),
                            request.paymentId()
                    )
                    .map(evaluation -> {
                        ensureReview(evaluation, request.merchantId());
                        return toResult(evaluation);
                    })
                    .orElseGet(() -> evaluateInitial(request));
        } catch (RiskConfigurationException | RiskProcessingException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new RiskProcessingException(
                    RiskProcessingError.UNEXPECTED_EVALUATION_FAILURE,
                    "Risk evaluation could not be completed",
                    exception
            );
        }
    }

    private RiskEvaluationResult evaluateInitial(RiskEvaluationRequest request) {
        RiskProfile profile = profileStore.loadActiveProfile(request.tenantId());
        RiskEvaluation candidate = evaluator.evaluate(
                RiskEvaluationId.newId(),
                request.tenantId(),
                request.paymentId(),
                request.amount(),
                request.currency(),
                profile,
                clock.instant()
        );
        RiskEvaluation stored = evaluationStore.appendInitialOrLoadExisting(candidate);
        ensureReview(stored, request.merchantId());
        return toResult(stored);
    }

    private void ensureReview(RiskEvaluation evaluation, java.util.UUID merchantId) {
        if (evaluation.decision() == com.ledgerops.risk.api.RiskDecision.MANUAL_REVIEW
                && riskReviewPort != null && riskReviewSlaPolicy != null) {
            riskReviewPort.createIfAbsent(new RiskReviewCreationRequest(
                    evaluation.tenantId(), evaluation.paymentId(), merchantId,
                    evaluation.evaluationId().value(),
                    evaluation.finalScore(), riskReviewSlaPolicy.version(), evaluation.evaluatedAt(),
                    riskReviewSlaPolicy.dueAt(evaluation.evaluatedAt(), evaluation.finalScore())));
        }
    }

    private RiskEvaluationResult toResult(RiskEvaluation evaluation) {
        return new RiskEvaluationResult(
                evaluation.profileId().value(),
                evaluation.profileVersion(),
                evaluation.uncappedScore(),
                evaluation.finalScore(),
                evaluation.decision(),
                evaluation.evaluationId().value()
        );
    }
}
