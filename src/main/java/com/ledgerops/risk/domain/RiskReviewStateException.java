package com.ledgerops.risk.domain;

public class RiskReviewStateException extends com.ledgerops.risk.api.RiskReviewConflictException {
    public RiskReviewStateException(String message) { super(message); }
}
