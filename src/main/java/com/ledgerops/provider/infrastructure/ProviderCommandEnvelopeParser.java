package com.ledgerops.provider.infrastructure;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.time.Instant;

final class ProviderCommandEnvelopeParser {

    private static final Set<String> ENVELOPE_FIELDS = Set.of(
            "messageId", "messageType", "schemaVersion", "aggregateId", "tenantId",
            "correlationId", "causationId", "occurredAt", "payload"
    );
    private final JsonMapper json = JsonMapper.builder().build();

    UUID trustworthyMessageId(String raw) {
        try {
            JsonNode root = json.readTree(raw);
            JsonNode value = root == null ? null : root.get("messageId");
            return value != null && value.isString() ? UUID.fromString(value.asString()) : null;
        } catch (Exception exception) {
            return null;
        }
    }

    ProviderCommandEnvelope parse(String raw) {
        try {
            JsonNode root = json.readTree(raw);
            if (root == null || !root.isObject()) {
                throw new PermanentlyInvalidMessageException("Envelope must be an object");
            }
            HashSet<String> fields = new HashSet<>();
            fields.addAll(root.propertyNames());
            if (!fields.equals(ENVELOPE_FIELDS)) {
                throw new PermanentlyInvalidMessageException("Envelope fields are invalid");
            }
            JsonNode payload = root.required("payload");
            if (!payload.isObject()) {
                throw new PermanentlyInvalidMessageException("Payload must be an object");
            }
            JsonNode schemaVersion = root.get("schemaVersion");
            if (schemaVersion == null || !schemaVersion.isIntegralNumber()
                    || !schemaVersion.canConvertToInt()) {
                throw new PermanentlyInvalidMessageException("schemaVersion must be an integer");
            }
            canonicalInstant(root);
            return new ProviderCommandEnvelope(
                    canonicalUuid(root, "messageId"),
                    text(root, "messageType"),
                    schemaVersion.intValue(),
                    canonicalUuid(root, "aggregateId"),
                    canonicalUuid(root, "tenantId"),
                    canonicalUuid(root, "correlationId"),
                    canonicalUuid(root, "causationId"),
                    payload,
                    json.writeValueAsString(payload)
            );
        } catch (PermanentlyInvalidMessageException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new PermanentlyInvalidMessageException("Envelope content is invalid", exception);
        }
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isString() || value.asString().isBlank()) {
            throw new PermanentlyInvalidMessageException(field + " must be a string");
        }
        return value.asString();
    }

    private UUID canonicalUuid(JsonNode root, String field) {
        String value = text(root, field);
        UUID parsed = UUID.fromString(value);
        if (!parsed.toString().equals(value)) {
            throw new PermanentlyInvalidMessageException(
                    field + " must be a canonical lowercase UUID"
            );
        }
        return parsed;
    }

    private void canonicalInstant(JsonNode root) {
        String value = text(root, "occurredAt");
        Instant parsed = Instant.parse(value);
        if (!parsed.toString().equals(value)) {
            throw new PermanentlyInvalidMessageException(
                    "occurredAt must be a canonical UTC instant"
            );
        }
    }
}
