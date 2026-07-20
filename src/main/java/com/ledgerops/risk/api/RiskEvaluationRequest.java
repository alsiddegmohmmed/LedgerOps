package com.ledgerops.risk.api;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Objects;
import java.util.UUID;

public record RiskEvaluationRequest(
        UUID tenantId,
        UUID paymentId,
        BigDecimal amount,
        Currency currency
) {

    public RiskEvaluationRequest {
        Objects.requireNonNull(tenantId, "Tenant ID must not be null");
        Objects.requireNonNull(paymentId, "Payment ID must not be null");
        Objects.requireNonNull(amount, "Payment amount must not be null");
        Objects.requireNonNull(currency, "Payment currency must not be null");
    }
}
