package com.ledgerops.ledger.domain;

import java.util.Objects;
import java.util.UUID;

public record LedgerSourceReference(
        UUID tenantId,
        LedgerSourceType sourceType,
        UUID sourceId
) {

    public LedgerSourceReference {
        Objects.requireNonNull(tenantId, "Tenant ID must not be null");
        Objects.requireNonNull(sourceType, "Ledger source type must not be null");
        Objects.requireNonNull(sourceId, "Ledger source ID must not be null");
    }
}
