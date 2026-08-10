package com.ledgerops.notification.api;

public record WebhookSecretResult(
        WebhookEndpoint endpoint,
        String plaintextSecret
) {
}
