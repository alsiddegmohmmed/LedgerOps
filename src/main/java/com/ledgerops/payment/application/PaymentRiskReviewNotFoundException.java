package com.ledgerops.payment.application;

import java.util.UUID;

public class PaymentRiskReviewNotFoundException
        extends com.ledgerops.payment.api.PaymentOperationNotFoundException {
    public PaymentRiskReviewNotFoundException(UUID reviewId) {
        super("RiskReview not found: " + reviewId);
    }
}
