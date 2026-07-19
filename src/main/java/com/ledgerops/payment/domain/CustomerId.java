package com.ledgerops.payment.domain;

import java.util.Objects;
import java.util.UUID;

public record CustomerId(UUID value) {

    public CustomerId {
        Objects.requireNonNull(value, "Customer ID must not be null");
    }

    public static CustomerId from(UUID value) {
        return new CustomerId(value);
    }
}
