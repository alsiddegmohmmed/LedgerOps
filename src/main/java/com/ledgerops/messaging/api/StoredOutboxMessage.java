package com.ledgerops.messaging.api;

import java.time.Instant;
import java.util.UUID;

public record StoredOutboxMessage(
        UUID outboxId,
        UUID messageId,
        ProducerName producerName,
        String deduplicationKey,
        String contentHash,
        String messageType,
        int schemaVersion,
        UUID aggregateId,
        UUID tenantId,
        String topic,
        String partitionKey,
        String canonicalPayloadJson,
        UUID correlationId,
        UUID causationId,
        Instant occurredAt
) {
}
