package com.ledgerops.notification.api;

import java.time.Instant;
import java.util.UUID;

public record WebhookDelivery(
        UUID deliveryId,
        UUID eventId,
        UUID tenantId,
        UUID merchantId,
        UUID endpointId,
        WebhookEndpointStatus endpointStatus,
        String eventType,
        String status,
        int attemptCount,
        Instant nextAttemptAt,
        Instant createdAt,
        Instant updatedAt,
        Integer lastHttpStatus,
        String lastOutcome,
        String lastSafeSummary
) {
}
