package com.ledgerops.ledger.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record LedgerStatementEntryResponse(
        UUID transactionId,
        int entryIndex,
        String sourceType,
        UUID sourceId,
        Instant postedAt,
        String direction,
        BigDecimal amount,
        String currency
) {
}
