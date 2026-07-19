package com.ledgerops.payment.domain;

public record IdempotencyKey(String value) {

    public IdempotencyKey {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Idempotency key must not be blank");
        }

        value = value.trim();
    }

    public static IdempotencyKey from(String value) {
        return new IdempotencyKey(value);
    }
}
