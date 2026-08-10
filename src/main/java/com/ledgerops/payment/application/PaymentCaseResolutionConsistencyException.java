package com.ledgerops.payment.application;

public class PaymentCaseResolutionConsistencyException
        extends com.ledgerops.payment.api.PaymentOperationConflictException {
    public PaymentCaseResolutionConsistencyException(String message) { super(message); }
}
