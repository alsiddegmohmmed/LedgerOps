package com.ledgerops.payment.application;

import com.ledgerops.payment.domain.Payment;

import java.util.Objects;

public record VersionedPayment(Payment payment, long version) {

    public VersionedPayment {
        Objects.requireNonNull(payment, "Payment must not be null");
        if (version < 0) {
            throw new IllegalArgumentException("Payment persistence version must not be negative");
        }
    }
}
