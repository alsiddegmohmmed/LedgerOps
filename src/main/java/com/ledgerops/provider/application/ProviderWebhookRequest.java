package com.ledgerops.provider.application;

import java.time.Instant;
import java.util.UUID;

public record ProviderWebhookRequest(
        byte[] rawBody,
        String keyId,
        String timestamp,
        String eventId,
        String signature,
        UUID correlationId,
        Instant receivedAt,
        String traceparent,
        String tracestate
) {
    public ProviderWebhookRequest(
            byte[] rawBody, String keyId, String timestamp, String eventId,
            String signature, UUID correlationId, Instant receivedAt
    ) {
        this(rawBody, keyId, timestamp, eventId, signature, correlationId, receivedAt,
                null, null);
    }
}
