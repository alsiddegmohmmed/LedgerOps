package com.ledgerops.reporting.api;

import java.time.Instant;
import java.util.UUID;

public record PaymentTimelineEntry(
        UUID sourceMessageId,
        UUID tenantId,
        UUID paymentId,
        UUID merchantId,
        String sourceModule,
        String sourceType,
        UUID sourceId,
        Instant occurredAt,
        String actorSource,
        String outcome,
        String reasonCode,
        UUID correlationId,
        String displayText
) {
}
