package com.ledgerops.risk.api;

public class RiskReviewAuthorizationFailure extends RuntimeException {
    public RiskReviewAuthorizationFailure(String message) {
        super(message);
    }
}
