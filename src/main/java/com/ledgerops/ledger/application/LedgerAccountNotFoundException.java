package com.ledgerops.ledger.application;

import com.ledgerops.ledger.domain.LedgerAccountId;

import java.util.UUID;

public final class LedgerAccountNotFoundException extends RuntimeException {

    private final UUID tenantId;
    private final LedgerAccountId accountId;

    public LedgerAccountNotFoundException(
            UUID tenantId,
            LedgerAccountId accountId
    ) {
        super("Ledger account does not exist for the tenant");
        this.tenantId = tenantId;
        this.accountId = accountId;
    }

    public UUID tenantId() {
        return tenantId;
    }

    public LedgerAccountId accountId() {
        return accountId;
    }
}
