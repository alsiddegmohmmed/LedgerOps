package com.ledgerops.payment.application;

import com.ledgerops.payment.domain.Payment;

import java.util.Objects;

public record PaymentCreationResult(
        Payment payment,
        boolean created
) {

    public PaymentCreationResult {
        Objects.requireNonNull(payment, "Payment must not be null");
    }
}
