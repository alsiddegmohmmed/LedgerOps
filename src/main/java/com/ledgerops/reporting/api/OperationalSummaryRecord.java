package com.ledgerops.reporting.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record OperationalSummaryRecord(
        String sourceType,
        UUID sourceId,
        UUID merchantId,
        Instant occurredAt,
        String sourceDetailHref
) {

    public OperationalSummaryRecord {
        Objects.requireNonNull(sourceType, "Summary source type must not be null");
        Objects.requireNonNull(sourceId, "Summary source ID must not be null");
        Objects.requireNonNull(occurredAt, "Summary occurrence time must not be null");
        if (sourceType.isBlank()) {
            throw new IllegalArgumentException("Summary source type must not be blank");
        }
        if (sourceDetailHref != null && sourceDetailHref.isBlank()) {
            throw new IllegalArgumentException("Summary source detail link must not be blank");
        }
    }
}
