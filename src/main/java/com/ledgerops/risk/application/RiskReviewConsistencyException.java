package com.ledgerops.risk.application;

public class RiskReviewConsistencyException extends com.ledgerops.risk.api.RiskReviewConflictException {
    public RiskReviewConsistencyException(String message) { super(message); }
}
