package com.ledgerops.ledger.domain;

import java.time.Instant;
import java.util.Currency;
import java.util.Objects;
import java.util.UUID;

public final class LedgerAccount {

    private final LedgerAccountId accountId;
    private final UUID tenantId;
    private final AccountCode accountCode;
    private final Currency currency;
    private final LedgerAccountStatus status;
    private final Instant createdAt;

    private LedgerAccount(
            LedgerAccountId accountId,
            UUID tenantId,
            AccountCode accountCode,
            Currency currency,
            Instant createdAt
    ) {
        this.accountId = Objects.requireNonNull(
                accountId,
                "Ledger account ID must not be null"
        );
        this.tenantId = Objects.requireNonNull(
                tenantId,
                "Tenant ID must not be null"
        );
        this.accountCode = Objects.requireNonNull(
                accountCode,
                "Account code must not be null"
        );
        this.currency = Objects.requireNonNull(
                currency,
                "Ledger account currency must not be null"
        );
        this.status = LedgerAccountStatus.ACTIVE;
        this.createdAt = Objects.requireNonNull(
                createdAt,
                "Ledger account creation time must not be null"
        );
    }

    public static LedgerAccount create(
            LedgerAccountId accountId,
            UUID tenantId,
            AccountCode accountCode,
            Currency currency,
            Instant createdAt
    ) {
        return new LedgerAccount(
                accountId,
                tenantId,
                accountCode,
                currency,
                createdAt
        );
    }

    public LedgerAccountId accountId() {
        return accountId;
    }

    public UUID tenantId() {
        return tenantId;
    }

    public AccountCode accountCode() {
        return accountCode;
    }

    public Currency currency() {
        return currency;
    }

    public LedgerAccountStatus status() {
        return status;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
