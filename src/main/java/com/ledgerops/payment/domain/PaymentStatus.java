package com.ledgerops.payment.domain;

public enum PaymentStatus {
    CREATED,
    VALIDATING,
    RISK_REVIEW,
    APPROVED,
    REJECTED,
    PROCESSING,
    COMPLETED,
    FAILED,
    REVERSED
}
