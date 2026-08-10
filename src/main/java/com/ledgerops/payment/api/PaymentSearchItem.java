package com.ledgerops.payment.api;

import com.ledgerops.risk.api.RiskDecision;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PaymentSearchItem(
        UUID paymentId,
        UUID tenantId,
        UUID merchantReference,
        UUID customerId,
        BigDecimal amount,
        String currency,
        String state,
        Instant createdAt,
        Instant updatedAt,
        RiskDecision riskDecision,
        String reconciliationStatus
) {
}
