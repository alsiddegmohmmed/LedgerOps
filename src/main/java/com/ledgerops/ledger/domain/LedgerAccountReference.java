package com.ledgerops.ledger.domain;

import java.util.Currency;
import java.util.Objects;
import java.util.UUID;

public record LedgerAccountReference(
        UUID tenantId,
        LedgerAccountId accountId,
        AccountCode accountCode,
        Currency currency
) {

    public LedgerAccountReference {
        Objects.requireNonNull(tenantId, "Tenant ID must not be null");
        Objects.requireNonNull(accountId, "Ledger account ID must not be null");
        Objects.requireNonNull(accountCode, "Account code must not be null");
        Objects.requireNonNull(currency, "Ledger account currency must not be null");
    }
}
