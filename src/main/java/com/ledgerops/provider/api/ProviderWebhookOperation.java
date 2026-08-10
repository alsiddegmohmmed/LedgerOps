package com.ledgerops.provider.api;

import java.time.Instant;
import java.util.UUID;

public record ProviderWebhookOperation(
        UUID eventId,
        UUID providerEventId,
        UUID paymentId,
        UUID attemptId,
        String resultCategory,
        String status,
        long receiptCount,
        Instant receivedAt,
        Instant updatedAt
) {
}
