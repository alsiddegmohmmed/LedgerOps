package com.ledgerops.payment.domain;

public record PaymentMethodCategory(String value) {

    public PaymentMethodCategory {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Payment method category must not be blank");
        }

        value = value.trim();
    }

    public static PaymentMethodCategory from(String value) {
        return new PaymentMethodCategory(value);
    }
}
