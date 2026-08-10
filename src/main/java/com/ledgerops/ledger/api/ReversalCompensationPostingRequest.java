package com.ledgerops.ledger.api;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Objects;
import java.util.UUID;

public record ReversalCompensationPostingRequest(
        UUID tenantId,
        UUID paymentId,
        UUID reversalId,
        BigDecimal amount,
        Currency currency
) {

    public ReversalCompensationPostingRequest {
        Objects.requireNonNull(tenantId, "Tenant ID must not be null");
        Objects.requireNonNull(paymentId, "Payment ID must not be null");
        Objects.requireNonNull(reversalId, "Reversal ID must not be null");
        Objects.requireNonNull(amount, "Reversal amount must not be null");
        Objects.requireNonNull(currency, "Reversal currency must not be null");
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("Reversal amount must be positive");
        }
    }
}
