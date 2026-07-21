package com.ledgerops.messaging.application;

import java.time.Instant;
import java.util.UUID;

public record OutboxClaim(
        UUID outboxId,
        UUID messageId,
        String messageType,
        int schemaVersion,
        UUID aggregateId,
        UUID tenantId,
        String topic,
        String partitionKey,
        String canonicalPayloadJson,
        UUID correlationId,
        UUID causationId,
        String traceparent,
        String tracestate,
        Instant occurredAt,
        int attemptCount,
        UUID leaseToken
) {
}
