package com.ledgerops.ledger.api;

import java.util.Objects;
import java.util.UUID;

public record SettlementCorrectionRequest(
        UUID tenantId,
        UUID correctionId,
        UUID originalTransactionId
) {

    public SettlementCorrectionRequest {
        Objects.requireNonNull(tenantId, "Tenant ID must not be null");
        Objects.requireNonNull(correctionId, "Correction ID must not be null");
        Objects.requireNonNull(
                originalTransactionId,
                "Original Ledger transaction ID must not be null"
        );
    }
}
