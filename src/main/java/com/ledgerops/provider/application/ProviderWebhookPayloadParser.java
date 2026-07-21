package com.ledgerops.provider.application;

public interface ProviderWebhookPayloadParser {
    ProviderWebhookPayload parse(byte[] rawBody, String authenticatedEventId);
}
