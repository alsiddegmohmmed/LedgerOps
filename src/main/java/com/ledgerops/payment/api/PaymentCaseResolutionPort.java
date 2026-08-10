package com.ledgerops.payment.api;

public interface PaymentCaseResolutionPort {
    PaymentCaseResolutionResult applyRiskResolution(PaymentCaseResolutionRequest request);
}
