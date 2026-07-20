package com.ledgerops.payment.application;

import com.ledgerops.risk.api.RiskEvaluationResult;

import java.util.Objects;

public record PaymentRiskDecisionResult(
        VersionedPayment payment,
        RiskEvaluationResult riskEvaluation
) {

    public PaymentRiskDecisionResult {
        Objects.requireNonNull(payment, "Versioned Payment must not be null");
        Objects.requireNonNull(riskEvaluation, "Risk evaluation result must not be null");
    }
}
