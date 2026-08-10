package com.ledgerops.payment.api;

import com.ledgerops.payment.application.PaymentRetryNowResult;

import java.time.Instant;
import java.util.UUID;

record PaymentRetryNowResponse(
        UUID paymentId,
        UUID providerWorkId,
        Instant previousDueAt,
        Instant dueAt,
        String status
) {
    static PaymentRetryNowResponse from(PaymentRetryNowResult result) {
        return new PaymentRetryNowResponse(
                result.paymentId(), result.providerWorkId(), result.previousDueAt(),
                result.dueAt(), "ACCELERATED");
    }
}
