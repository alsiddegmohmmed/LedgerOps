package com.ledgerops.risk.api;

public interface RiskEvaluationUseCase {

    RiskEvaluationResult evaluate(RiskEvaluationRequest request);
}
