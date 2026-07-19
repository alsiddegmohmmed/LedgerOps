package com.ledgerops.payment.application;

import com.ledgerops.payment.domain.Payment;

import java.util.Objects;

public record StoredPayment(
        Payment payment,
        String requestFingerprint,
        boolean created
) {

    public StoredPayment {
        Objects.requireNonNull(payment, "Stored payment must not be null");
        Objects.requireNonNull(
                requestFingerprint,
                "Stored request fingerprint must not be null"
        );
    }
}
