package com.ledgerops.payment.domain;

import java.util.Objects;
import java.util.UUID;

public record PaymentId(UUID value) {

    public PaymentId {
        Objects.requireNonNull(value, "Payment ID must not be null");
    }

    public static PaymentId newId() {
        return new PaymentId(UUID.randomUUID());
    }

    public static PaymentId from(UUID value) {
        return new PaymentId(value);
    }
}
