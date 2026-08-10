package com.ledgerops.ledger.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record LedgerStatementResponse(
        UUID accountId,
        String currency,
        Instant fromInclusive,
        Instant toExclusive,
        BigDecimal totalDebits,
        BigDecimal totalCredits,
        long totalEntries,
        int offset,
        int limit,
        List<LedgerStatementEntryResponse> entries
) {}
