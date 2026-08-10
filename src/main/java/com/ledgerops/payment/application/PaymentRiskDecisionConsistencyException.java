package com.ledgerops.payment.application;

public final class PaymentRiskDecisionConsistencyException
        extends com.ledgerops.payment.api.PaymentOperationConflictException {

    public PaymentRiskDecisionConsistencyException(String message) {
        super(message);
    }
}
