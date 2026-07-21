package com.ledgerops.messaging.application;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

public record MessageEnvelope(
        UUID messageId,
        String messageType,
        int schemaVersion,
        UUID aggregateId,
        UUID tenantId,
        UUID correlationId,
        UUID causationId,
        Instant occurredAt,
        JsonNode payload
) {
}
