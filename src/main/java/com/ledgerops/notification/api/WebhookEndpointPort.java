package com.ledgerops.notification.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface WebhookEndpointPort {

    WebhookSecretResult create(
            UUID tenantId,
            UUID merchantId,
            String label,
            String endpointUrl,
            java.util.Set<WebhookEventType> allowedEventTypes,
            Instant now
    );

    WebhookSecretResult rotate(UUID tenantId, UUID merchantId, UUID endpointId, Instant now);

    WebhookEndpoint revoke(UUID tenantId, UUID merchantId, UUID endpointId, Instant now);

    List<WebhookEndpoint> list(UUID tenantId, UUID merchantId);

    WebhookDelivery trigger(
            UUID tenantId,
            UUID merchantId,
            UUID endpointId,
            WebhookEventType eventType,
            Map<String, Object> payload,
            Instant now
    );

    List<WebhookDelivery> deliveries(UUID tenantId, UUID merchantId, UUID endpointId);
}
