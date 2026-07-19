package com.ledgerops.customer.domain;

public record CustomerReference(String value) {

    private static final int MAX_LENGTH = 120;

    public CustomerReference {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Customer reference must not be blank");
        }

        value = value.trim();

        if (value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "Customer reference must not exceed " + MAX_LENGTH + " characters"
            );
        }
    }

    public static CustomerReference from(String value) {
        return new CustomerReference(value);
    }
}
