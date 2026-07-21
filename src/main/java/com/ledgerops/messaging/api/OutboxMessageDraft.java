package com.ledgerops.messaging.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record OutboxMessageDraft(
        ProducerName producerName,
        String deduplicationKey,
        String messageType,
        int schemaVersion,
        UUID aggregateId,
        UUID tenantId,
        String topic,
        String partitionKey,
        String canonicalPayloadJson,
        UUID correlationId,
        UUID causationId,
        Instant occurredAt,
        String traceparent,
        String tracestate
) {
    public OutboxMessageDraft(
            ProducerName producerName, String deduplicationKey, String messageType,
            int schemaVersion, UUID aggregateId, UUID tenantId, String topic,
            String partitionKey, String canonicalPayloadJson, UUID correlationId,
            UUID causationId, Instant occurredAt
    ) {
        this(producerName, deduplicationKey, messageType, schemaVersion, aggregateId,
                tenantId, topic, partitionKey, canonicalPayloadJson, correlationId,
                causationId, occurredAt, null, null);
    }

    public OutboxMessageDraft {
        Objects.requireNonNull(producerName, "Producer name must not be null");
        deduplicationKey = requireText(deduplicationKey, "Deduplication key");
        messageType = requireText(messageType, "Message type");
        if (schemaVersion != 1) {
            throw new IllegalArgumentException("Release 0.2 schema version must be 1");
        }
        Objects.requireNonNull(aggregateId, "Aggregate ID must not be null");
        Objects.requireNonNull(tenantId, "Tenant ID must not be null");
        topic = requireText(topic, "Topic");
        partitionKey = requireText(partitionKey, "Partition key");
        canonicalPayloadJson = requireText(canonicalPayloadJson, "Canonical payload");
        if (!canonicalPayloadJson.startsWith("{") || !canonicalPayloadJson.endsWith("}")) {
            throw new IllegalArgumentException("Canonical payload must be a JSON object");
        }
        Objects.requireNonNull(correlationId, "Correlation ID must not be null");
        Objects.requireNonNull(causationId, "Causation ID must not be null");
        Objects.requireNonNull(occurredAt, "Occurred-at time must not be null");
        if (traceparent != null && !traceparent.matches(
                "00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}")) {
            throw new IllegalArgumentException("Traceparent must be canonical W3C version 00");
        }
        if (tracestate != null && tracestate.length() > 512) {
            throw new IllegalArgumentException("Tracestate must not exceed 512 characters");
        }
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return value;
    }
}
