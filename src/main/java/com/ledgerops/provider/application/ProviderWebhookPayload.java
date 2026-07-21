package com.ledgerops.provider.application;

import com.ledgerops.provider.api.ProviderResultCategory;

import java.time.Instant;
import java.util.UUID;

public record ProviderWebhookPayload(
        UUID providerEventId,
        UUID providerResultId,
        String providerIdempotencyKey,
        String providerReference,
        ProviderResultCategory category,
        Instant providerOccurredAt,
        String canonicalJson
) {
}
