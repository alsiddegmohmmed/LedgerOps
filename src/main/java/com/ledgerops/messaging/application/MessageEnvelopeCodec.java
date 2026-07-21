package com.ledgerops.messaging.application;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.UUID;

@Component
public class MessageEnvelopeCodec {

    private static final Set<String> FIELDS = Set.of(
            "messageId", "messageType", "schemaVersion", "aggregateId", "tenantId",
            "correlationId", "causationId", "occurredAt", "payload"
    );
    private final JsonMapper json = JsonMapper.builder().build();

    public String encode(OutboxClaim claim) {
        LinkedHashMap<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("messageId", claim.messageId());
        envelope.put("messageType", claim.messageType());
        envelope.put("schemaVersion", claim.schemaVersion());
        envelope.put("aggregateId", claim.aggregateId());
        envelope.put("tenantId", claim.tenantId());
        envelope.put("correlationId", claim.correlationId());
        envelope.put("causationId", claim.causationId());
        envelope.put("occurredAt", claim.occurredAt());
        envelope.put("payload", parsePayload(claim.canonicalPayloadJson()));
        try {
            return json.writeValueAsString(envelope);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Cannot encode message envelope", exception);
        }
    }

    public UUID trustworthyMessageId(String raw) {
        try {
            JsonNode root = json.readTree(raw);
            if (root == null || !root.isObject() || !root.has("messageId")
                    || !root.get("messageId").isString()) {
                return null;
            }
            return UUID.fromString(root.get("messageId").asString());
        } catch (Exception exception) {
            return null;
        }
    }

    public MessageEnvelope decode(String raw) {
        try {
            JsonNode root = json.readTree(raw);
            if (root == null || !root.isObject()) {
                throw new InvalidEnvelopeException("Envelope must be a JSON object");
            }
            Set<String> names = new java.util.HashSet<>();
            names.addAll(root.propertyNames());
            if (!names.equals(FIELDS)) {
                throw new InvalidEnvelopeException("Envelope fields do not match version 1");
            }
            JsonNode payload = root.get("payload");
            if (payload == null || !payload.isObject()) {
                throw new InvalidEnvelopeException("Envelope payload must be an object");
            }
            JsonNode schemaVersion = root.get("schemaVersion");
            if (schemaVersion == null || !schemaVersion.isIntegralNumber()
                    || !schemaVersion.canConvertToInt()) {
                throw new InvalidEnvelopeException("schemaVersion must be an integer");
            }
            return new MessageEnvelope(
                    canonicalUuid(root, "messageId"),
                    requiredText(root, "messageType"),
                    schemaVersion.intValue(),
                    canonicalUuid(root, "aggregateId"),
                    canonicalUuid(root, "tenantId"),
                    canonicalUuid(root, "correlationId"),
                    canonicalUuid(root, "causationId"),
                    canonicalInstant(root),
                    payload
            );
        } catch (InvalidEnvelopeException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new InvalidEnvelopeException("Envelope cannot be parsed", exception);
        }
    }

    private JsonNode parsePayload(String payload) {
        try {
            return json.readTree(payload);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Outbox payload is invalid", exception);
        }
    }

    private String requiredText(JsonNode root, String field) {
        JsonNode value = root.get(field);
        if (value == null || !value.isString() || value.asString().isBlank()) {
            throw new InvalidEnvelopeException(field + " must be a nonblank string");
        }
        return value.asString();
    }

    private UUID canonicalUuid(JsonNode root, String field) {
        String value = requiredText(root, field);
        UUID parsed = UUID.fromString(value);
        if (!parsed.toString().equals(value)) {
            throw new InvalidEnvelopeException(field + " must be a canonical lowercase UUID");
        }
        return parsed;
    }

    private Instant canonicalInstant(JsonNode root) {
        String value = requiredText(root, "occurredAt");
        Instant parsed = Instant.parse(value);
        if (!parsed.toString().equals(value)) {
            throw new InvalidEnvelopeException("occurredAt must be a canonical UTC instant");
        }
        return parsed;
    }
}
