package com.ledgerops.ledger.api;

import java.util.Objects;
import java.util.UUID;

public record LedgerSettlementSource(
        LedgerSettlementSourceType sourceType,
        UUID sourceId
) {

    public LedgerSettlementSource {
        Objects.requireNonNull(sourceType, "Ledger source type must not be null");
        Objects.requireNonNull(sourceId, "Ledger source ID must not be null");
    }
}
