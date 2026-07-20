package com.ledgerops.ledger.application;

import com.ledgerops.ledger.domain.LedgerAccountId;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public interface LedgerAccountQueryStore {

    LedgerBalanceData summarizeBefore(
            UUID tenantId,
            LedgerAccountId accountId,
            Instant toExclusive
    );

    LedgerStatementData findStatement(LedgerStatementQuery query);

    record LedgerBalanceData(BigDecimal totalDebits, BigDecimal totalCredits) {
    }
}
