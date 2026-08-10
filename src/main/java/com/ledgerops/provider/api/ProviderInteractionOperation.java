package com.ledgerops.provider.api;

import java.time.Instant;
import java.util.UUID;

public record ProviderInteractionOperation(
        UUID interactionId,
        UUID tenantId,
        UUID workId,
        UUID webhookEventId,
        UUID paymentId,
        ProviderOperationType operationType,
        UUID operationId,
        UUID attemptId,
        String providerId,
        String workType,
        UUID requestId,
        Integer httpStatus,
        String communicationOutcome,
        long latencyMillis,
        String safeErrorCode,
        Instant startedAt,
        Instant completedAt
) {

    public ProviderInteractionOperation(
            UUID interactionId,
            UUID tenantId,
            UUID workId,
            UUID webhookEventId,
            UUID paymentId,
            UUID attemptId,
            String providerId,
            String workType,
            UUID requestId,
            Integer httpStatus,
            String communicationOutcome,
            long latencyMillis,
            String safeErrorCode,
            Instant startedAt,
            Instant completedAt
    ) {
        this(interactionId, tenantId, workId, webhookEventId, paymentId,
                ProviderOperationType.PAYMENT, paymentId, attemptId, providerId,
                workType, requestId, httpStatus, communicationOutcome, latencyMillis,
                safeErrorCode, startedAt, completedAt);
    }
}
