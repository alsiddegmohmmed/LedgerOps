package com.ledgerops.provider.application;

import com.ledgerops.provider.api.ProviderResultCategory;

import java.time.Instant;
import java.util.UUID;

public record ProviderWebhookClaim(
        UUID eventId,
        UUID tenantId,
        UUID paymentId,
        UUID attemptId,
        int attemptSequence,
        String providerId,
        String providerIdempotencyKey,
        UUID providerEventId,
        UUID providerResultId,
        String providerReference,
        ProviderResultCategory category,
        Instant providerOccurredAt,
        String payloadHash,
        UUID correlationId,
        String traceparent,
        String tracestate,
        Instant receivedAt,
        UUID leaseToken,
        Instant leaseExpiresAt
) {
}
