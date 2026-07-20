package com.ledgerops.ledger.application;

import com.ledgerops.ledger.domain.LedgerAccountId;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record LedgerStatementQuery(
        UUID tenantId,
        LedgerAccountId accountId,
        Instant fromInclusive,
        Instant toExclusive,
        int offset,
        int limit
) {

    public static final int MAXIMUM_LIMIT = 100;

    public LedgerStatementQuery {
        Objects.requireNonNull(tenantId, "Tenant ID must not be null");
        Objects.requireNonNull(accountId, "Ledger account ID must not be null");
        Objects.requireNonNull(fromInclusive, "Statement start must not be null");
        Objects.requireNonNull(toExclusive, "Statement end must not be null");
        if (!fromInclusive.isBefore(toExclusive)) {
            throw new IllegalArgumentException(
                    "Statement start must be before its exclusive end"
            );
        }
        if (offset < 0) {
            throw new IllegalArgumentException("Statement offset must not be negative");
        }
        if (limit < 1 || limit > MAXIMUM_LIMIT) {
            throw new IllegalArgumentException(
                    "Statement limit must be between 1 and " + MAXIMUM_LIMIT
            );
        }
    }
}
