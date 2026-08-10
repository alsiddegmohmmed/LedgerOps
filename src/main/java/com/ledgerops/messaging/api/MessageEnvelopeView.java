package com.ledgerops.messaging.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Validated version-1 message envelope exposed to consuming modules.
 */
public record MessageEnvelopeView(
        UUID messageId,
        String messageType,
        int schemaVersion,
        UUID aggregateId,
        UUID tenantId,
        UUID correlationId,
        UUID causationId,
        Instant occurredAt,
        String payloadJson
) {
}
