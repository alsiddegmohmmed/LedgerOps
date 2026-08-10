package com.ledgerops.risk.application;

import java.util.UUID;

public class RiskReviewNotFoundException extends RuntimeException {
    public RiskReviewNotFoundException(UUID reviewId) { super("RiskReview not found: " + reviewId); }
}
