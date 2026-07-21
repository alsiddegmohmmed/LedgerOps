package com.ledgerops.provider.infrastructure;

import tools.jackson.databind.JsonNode;

import java.util.UUID;

record ProviderCommandEnvelope(
        UUID messageId,
        String messageType,
        int schemaVersion,
        UUID aggregateId,
        UUID tenantId,
        UUID correlationId,
        UUID causationId,
        JsonNode payload,
        String canonicalPayload
) {
}
