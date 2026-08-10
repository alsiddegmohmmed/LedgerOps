package com.ledgerops.provider.api;

import java.time.Instant;
import java.util.UUID;

public record ProviderWorkOperation(
        UUID workId,
        UUID tenantId,
        UUID paymentId,
        ProviderOperationType operationType,
        UUID operationId,
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

    public ProviderWorkOperation(
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
        this(workId, tenantId, paymentId, ProviderOperationType.PAYMENT, paymentId, attemptId,
                attemptSequence, workType, status, providerId, providerIdempotencyKey,
                dueAt, executionCount, transportRetryCount, lastErrorCode,
                scenarioProfileId, scenarioProfileVersion, createdAt, updatedAt);
    }
}
