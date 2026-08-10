package com.ledgerops.notification.api;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public record WebhookEndpoint(
        UUID endpointId,
        UUID tenantId,
        UUID merchantId,
        String label,
        String endpointUrl,
        WebhookEndpointStatus status,
        String keyVersion,
        Set<WebhookEventType> allowedEventTypes,
        Instant createdAt,
        Instant rotatedAt,
        Instant revokedAt
) {
    public WebhookEndpoint {
        allowedEventTypes = Set.copyOf(allowedEventTypes);
    }
}
