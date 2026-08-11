package com.ledgerops.reconciliation.api;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record SettlementValidationItemSnapshot(
        UUID validationItemId,
        long rowNumber,
        String reasonCode,
        Map<String, Object> safeEvidence,
        Instant createdAt
) {
}
