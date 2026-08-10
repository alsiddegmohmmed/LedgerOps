package com.ledgerops.payment.api;

import com.ledgerops.payment.domain.PaymentStatus;
import com.ledgerops.risk.api.RiskReviewSnapshot;

import java.util.Objects;

public record PaymentManualRiskDecisionResult(
        RiskReviewSnapshot review,
        PaymentStatus paymentStatus,
        boolean paymentChanged
) {
    public PaymentManualRiskDecisionResult {
        Objects.requireNonNull(review, "Risk review must not be null");
        Objects.requireNonNull(paymentStatus, "Payment status must not be null");
    }
}
