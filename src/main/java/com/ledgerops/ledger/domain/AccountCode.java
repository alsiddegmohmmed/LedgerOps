package com.ledgerops.ledger.domain;

import java.util.Objects;

public enum AccountCode {
    CUSTOMER_RECEIVABLE,
    MERCHANT_PAYABLE,
    PROVIDER_CLEARING,
    PLATFORM_FEE_REVENUE,
    REVERSAL_PAYABLE,
    SETTLEMENT_RECEIVABLE;

    public static AccountCode from(String value) {
        Objects.requireNonNull(value, "Account code must not be null");
        try {
            return valueOf(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "Unsupported Release 0.1 account code: " + value,
                    exception
            );
        }
    }

    public String value() {
        return name();
    }
}
