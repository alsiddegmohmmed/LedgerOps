package com.ledgerops.risk.application;

import com.ledgerops.risk.domain.RiskEvaluation;

import java.util.Optional;
import java.util.UUID;

public interface RiskEvaluationStore {

    RiskEvaluation appendInitialOrLoadExisting(RiskEvaluation evaluation);

    Optional<RiskEvaluation> findByTenantAndPayment(UUID tenantId, UUID paymentId);
}
