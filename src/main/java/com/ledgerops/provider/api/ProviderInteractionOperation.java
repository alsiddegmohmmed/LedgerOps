package com.ledgerops.provider.api;

import java.time.Instant;
import java.util.UUID;

public record ProviderInteractionOperation(
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
}
