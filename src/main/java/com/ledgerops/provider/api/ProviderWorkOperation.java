package com.ledgerops.provider.api;

import java.time.Instant;
import java.util.UUID;

public record ProviderWorkOperation(
        UUID workId,
        UUID tenantId,
        UUID paymentId,
        UUID attemptId,
        int attemptSequence,
        String workType,
        String status,
        String providerId,
        String providerIdempotencyKey,
        Instant dueAt,
        int executionCount,
        int transportRetryCount,
        String lastErrorCode,
        UUID scenarioProfileId,
        Long scenarioProfileVersion,
        Instant createdAt,
        Instant updatedAt
) {
}
