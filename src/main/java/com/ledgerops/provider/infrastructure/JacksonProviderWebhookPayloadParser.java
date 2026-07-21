package com.ledgerops.provider.infrastructure;

import com.ledgerops.provider.api.ProviderResultCategory;
import com.ledgerops.provider.application.InvalidProviderWebhookPayloadException;
import com.ledgerops.provider.application.ProviderWebhookPayload;
import com.ledgerops.provider.application.ProviderWebhookPayloadParser;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.UUID;

final class JacksonProviderWebhookPayloadParser implements ProviderWebhookPayloadParser {
    private final JsonMapper json = JsonMapper.builder().build();

    @Override
    public ProviderWebhookPayload parse(byte[] rawBody, String authenticatedEventId) {
        try {
            JsonNode value = json.readTree(rawBody);
            if (value == null || !value.isObject()) {
                throw invalid("Webhook payload must be a JSON object");
            }
            UUID eventId = uuid(value, "providerEventId");
            if (!eventId.toString().equals(authenticatedEventId)) {
                throw invalid("Authenticated event ID does not match payload");
            }
            UUID resultId = uuid(value, "providerResultId");
            String providerKey = text(value, "providerIdempotencyKey");
            if (!providerKey.matches("payment:[0-9a-f-]{36}")) {
                throw invalid("Provider idempotency key is invalid");
            }
            ProviderResultCategory category = ProviderResultCategory.valueOf(
                    text(value, "providerResultCategory"));
            Instant occurredAt = Instant.parse(text(value, "providerOccurredAt"));
            if (!occurredAt.toString().equals(text(value, "providerOccurredAt"))) {
                throw invalid("Provider occurred-at must be a canonical UTC instant");
            }
            String reference = optionalText(value, "providerReference");
            return new ProviderWebhookPayload(
                    eventId, resultId, providerKey, reference, category, occurredAt,
                    json.writeValueAsString(value));
        } catch (InvalidProviderWebhookPayloadException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new InvalidProviderWebhookPayloadException(
                    "Webhook payload is malformed", exception);
        }
    }

    private UUID uuid(JsonNode value, String field) {
        String text = text(value, field);
        UUID parsed = UUID.fromString(text);
        if (!parsed.toString().equals(text)) {
            throw invalid(field + " must be a canonical UUID");
        }
        return parsed;
    }

    private String text(JsonNode value, String field) {
        JsonNode node = value.get(field);
        if (node == null || !node.isString() || node.asString().isBlank()) {
            throw invalid(field + " must be a nonblank string");
        }
        return node.asString();
    }

    private String optionalText(JsonNode value, String field) {
        JsonNode node = value.get(field);
        if (node == null) return null;
        if (!node.isString() || node.asString().isBlank()) {
            throw invalid(field + " must be a nonblank string when present");
        }
        return node.asString();
    }

    private InvalidProviderWebhookPayloadException invalid(String message) {
        return new InvalidProviderWebhookPayloadException(message);
    }
}
