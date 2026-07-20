package com.ledgerops.risk.application;

import com.ledgerops.risk.api.RiskConfigurationException;
import com.ledgerops.risk.api.RiskEvaluationRequest;
import com.ledgerops.risk.api.RiskEvaluationResult;
import com.ledgerops.risk.api.RiskEvaluationUseCase;
import com.ledgerops.risk.api.RiskProcessingError;
import com.ledgerops.risk.api.RiskProcessingException;
import com.ledgerops.risk.domain.RiskEvaluation;
import com.ledgerops.risk.domain.RiskEvaluationId;
import com.ledgerops.risk.domain.RiskEvaluator;
import com.ledgerops.risk.domain.RiskProfile;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Objects;

@Service
public class RiskEvaluationService implements RiskEvaluationUseCase {

    private final RiskProfileStore profileStore;
    private final RiskEvaluationStore evaluationStore;
    private final RiskEvaluator evaluator;
    private final Clock clock;

    public RiskEvaluationService(
            RiskProfileStore profileStore,
            RiskEvaluationStore evaluationStore,
            RiskEvaluator evaluator,
            Clock clock
    ) {
        this.profileStore = profileStore;
        this.evaluationStore = evaluationStore;
        this.evaluator = evaluator;
        this.clock = clock;
    }

    @Override
    public RiskEvaluationResult evaluate(RiskEvaluationRequest request) {
        Objects.requireNonNull(request, "Risk evaluation request must not be null");

        try {
            return evaluationStore.findByTenantAndPayment(
                            request.tenantId(),
                            request.paymentId()
                    )
                    .map(this::toResult)
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
        return toResult(evaluationStore.appendInitialOrLoadExisting(candidate));
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
