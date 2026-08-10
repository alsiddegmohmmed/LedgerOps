package com.ledgerops.risk.application;

import java.util.UUID;

public class RiskReviewConcurrencyException extends com.ledgerops.risk.api.RiskReviewConflictException {
    public RiskReviewConcurrencyException(UUID reviewId) { super("RiskReview changed concurrently: " + reviewId); }
}
