package com.ledgerops.payment.infrastructure;

import tools.jackson.databind.JsonNode;

import java.util.UUID;

record ProviderResultEnvelope(
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
