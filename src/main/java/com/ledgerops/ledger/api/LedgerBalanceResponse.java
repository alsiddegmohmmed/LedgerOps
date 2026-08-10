package com.ledgerops.ledger.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record LedgerBalanceResponse(
        UUID accountId,
        String currency,
        BigDecimal totalDebits,
        BigDecimal totalCredits,
        Instant asOfExclusive
) {
}
