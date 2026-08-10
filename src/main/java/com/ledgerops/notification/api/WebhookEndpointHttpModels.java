package com.ledgerops.notification.api;

import com.ledgerops.notification.application.WebhookEndpointService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

record WebhookEndpointRequest(
        @NotBlank @Size(max = 120) String label,
        @NotBlank String endpointUrl,
        @NotEmpty Set<@NotBlank String> allowedEventTypes
) {
    Set<WebhookEventType> eventTypes() {
        return allowedEventTypes.stream().map(WebhookEventType::fromValue).collect(java.util.stream.Collectors.toSet());
    }
}

record WebhookTestEventRequest(
        @NotBlank String eventType,
        Map<String, Object> payload
) {
    WebhookEventType type() {
        return WebhookEventType.fromValue(eventType);
    }
}

record WebhookEndpointResponse(
        UUID endpointId,
        UUID tenantId,
        UUID merchantId,
        String label,
        String endpointUrl,
        String status,
        String keyVersion,
        Set<String> allowedEventTypes,
        Instant createdAt,
        Instant rotatedAt,
        Instant revokedAt
) {
    static WebhookEndpointResponse from(WebhookEndpoint endpoint) {
        return new WebhookEndpointResponse(
                endpoint.endpointId(), endpoint.tenantId(), endpoint.merchantId(), endpoint.label(),
                endpoint.endpointUrl(), endpoint.status().name(), endpoint.keyVersion(),
                endpoint.allowedEventTypes().stream().map(WebhookEventType::value)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet()),
                endpoint.createdAt(), endpoint.rotatedAt(), endpoint.revokedAt());
    }
}

record WebhookSecretResponse(
        WebhookEndpointResponse endpoint,
        String plaintextSecret
) {
    static WebhookSecretResponse from(WebhookSecretResult result) {
        return new WebhookSecretResponse(
                WebhookEndpointResponse.from(result.endpoint()), result.plaintextSecret());
    }
}

record WebhookDeliveryResponse(
        UUID deliveryId,
        UUID eventId,
        UUID tenantId,
        UUID merchantId,
        UUID endpointId,
        String endpointStatus,
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
    static WebhookDeliveryResponse from(WebhookDelivery delivery) {
        return new WebhookDeliveryResponse(
                delivery.deliveryId(), delivery.eventId(), delivery.tenantId(), delivery.merchantId(),
                delivery.endpointId(), delivery.endpointStatus().name(), delivery.eventType(),
                delivery.status(), delivery.attemptCount(), delivery.nextAttemptAt(),
                delivery.createdAt(), delivery.updatedAt(), delivery.lastHttpStatus(),
                delivery.lastOutcome(), delivery.lastSafeSummary());
    }
}
