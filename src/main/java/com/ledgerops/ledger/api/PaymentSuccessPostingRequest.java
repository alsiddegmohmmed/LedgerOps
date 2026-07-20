package com.ledgerops.ledger.api;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Objects;
import java.util.UUID;

public record PaymentSuccessPostingRequest(
        UUID tenantId,
        UUID paymentId,
        BigDecimal amount,
        Currency currency
) {

    public PaymentSuccessPostingRequest {
        Objects.requireNonNull(tenantId, "Tenant ID must not be null");
        Objects.requireNonNull(paymentId, "Payment ID must not be null");
        Objects.requireNonNull(amount, "Payment amount must not be null");
        Objects.requireNonNull(currency, "Payment currency must not be null");
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("Payment amount must be positive");
        }
    }
}
