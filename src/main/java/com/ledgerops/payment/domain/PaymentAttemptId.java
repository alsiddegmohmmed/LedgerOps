package com.ledgerops.payment.domain;

import java.util.Objects;
import java.util.UUID;

public record PaymentAttemptId(UUID value) {

    public PaymentAttemptId {
        Objects.requireNonNull(value, "Payment Attempt ID must not be null");
    }

    public static PaymentAttemptId from(UUID value) {
        return new PaymentAttemptId(value);
    }
}
