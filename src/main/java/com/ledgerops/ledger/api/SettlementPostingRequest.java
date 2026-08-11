package com.ledgerops.ledger.api;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Objects;
import java.util.UUID;

public record SettlementPostingRequest(
        UUID tenantId,
        UUID settlementPostingId,
        SettlementPostingType postingType,
        BigDecimal amount,
        Currency currency
) {

    public SettlementPostingRequest {
        Objects.requireNonNull(tenantId, "Tenant ID must not be null");
        Objects.requireNonNull(settlementPostingId, "Settlement posting ID must not be null");
        Objects.requireNonNull(postingType, "Settlement posting type must not be null");
        Objects.requireNonNull(amount, "Settlement amount must not be null");
        Objects.requireNonNull(currency, "Settlement currency must not be null");
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("Settlement amount must be positive");
        }
    }
}
