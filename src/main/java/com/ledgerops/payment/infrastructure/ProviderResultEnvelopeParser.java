package com.ledgerops.payment.infrastructure;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

final class ProviderResultEnvelopeParser {

    private static final Set<String> ENVELOPE_FIELDS = Set.of(
            "messageId", "messageType", "schemaVersion", "aggregateId", "tenantId",
            "correlationId", "causationId", "occurredAt", "payload"
    );
    private final JsonMapper json = JsonMapper.builder().build();

    UUID trustworthyMessageId(String raw) {
        try {
            JsonNode root = json.readTree(raw);
            JsonNode value = root == null ? null : root.get("messageId");
            if (value == null || !value.isString()) {
                return null;
            }
            UUID parsed = UUID.fromString(value.asString());
            return parsed.toString().equals(value.asString()) ? parsed : null;
        } catch (Exception exception) {
            return null;
        }
    }

    ProviderResultEnvelope parse(String raw) {
        try {
            JsonNode root = json.readTree(raw);
            if (root == null || !root.isObject()) {
                throw new InvalidProviderResultMessageException("Envelope must be an object");
            }
            HashSet<String> fields = new HashSet<>();
            fields.addAll(root.propertyNames());
            if (!fields.equals(ENVELOPE_FIELDS)) {
                throw new InvalidProviderResultMessageException("Envelope fields are invalid");
            }
            JsonNode payload = root.required("payload");
            if (!payload.isObject()) {
                throw new InvalidProviderResultMessageException("Payload must be an object");
            }
            JsonNode version = root.get("schemaVersion");
            if (version == null || !version.isIntegralNumber() || !version.canConvertToInt()) {
                throw new InvalidProviderResultMessageException(
                        "schemaVersion must be an integer"
                );
            }
            canonicalInstant(root, "occurredAt");
            return new ProviderResultEnvelope(
                    canonicalUuid(root, "messageId"),
                    text(root, "messageType"),
                    version.intValue(),
                    canonicalUuid(root, "aggregateId"),
                    canonicalUuid(root, "tenantId"),
                    canonicalUuid(root, "correlationId"),
                    canonicalUuid(root, "causationId"),
                    payload,
                    json.writeValueAsString(payload)
            );
        } catch (InvalidProviderResultMessageException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new InvalidProviderResultMessageException(
                    "Envelope content is invalid",
                    exception
            );
        }
    }

    UUID canonicalUuid(JsonNode node, String field) {
        String value = text(node, field);
        UUID parsed = UUID.fromString(value);
        if (!parsed.toString().equals(value)) {
            throw new InvalidProviderResultMessageException(
                    field + " must be a canonical lowercase UUID"
            );
        }
        return parsed;
    }

    Instant canonicalInstant(JsonNode node, String field) {
        String value = text(node, field);
        Instant parsed = Instant.parse(value);
        if (!parsed.toString().equals(value)) {
            throw new InvalidProviderResultMessageException(
                    field + " must be a canonical UTC instant"
            );
        }
        return parsed;
    }

    String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isString() || value.asString().isBlank()) {
            throw new InvalidProviderResultMessageException(field + " must be a string");
        }
        return value.asString();
    }

    String optionalText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null) {
            return null;
        }
        if (!value.isString() || value.asString().isBlank()) {
            throw new InvalidProviderResultMessageException(
                    field + " must be a nonblank string when present"
            );
        }
        return value.asString();
    }
}
