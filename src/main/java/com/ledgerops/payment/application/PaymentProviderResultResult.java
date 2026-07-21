package com.ledgerops.payment.application;

import com.ledgerops.payment.domain.PaymentStatus;

import java.util.Objects;
import java.util.UUID;

public record PaymentProviderResultResult(
        UUID paymentId,
        PaymentStatus paymentStatus,
        PaymentProviderResultOutcome outcome,
        UUID lifecycleMessageId,
        UUID ledgerTransactionId
) {
    public PaymentProviderResultResult {
        Objects.requireNonNull(paymentId, "Payment ID must not be null");
        Objects.requireNonNull(paymentStatus, "Payment status must not be null");
        Objects.requireNonNull(outcome, "Outcome must not be null");
    }
}
