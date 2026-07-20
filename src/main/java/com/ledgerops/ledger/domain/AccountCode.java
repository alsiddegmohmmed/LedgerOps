package com.ledgerops.ledger.domain;

import java.util.Objects;

public record AccountCode(String value) {

    public AccountCode {
        Objects.requireNonNull(value, "Account code must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException("Account code must not be blank");
        }
    }

    public static AccountCode from(String value) {
        return new AccountCode(value);
    }
}
