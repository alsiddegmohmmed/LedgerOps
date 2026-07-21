package com.ledgerops.provider.application;

import java.time.Instant;
import java.util.UUID;

public record ProviderWorkClaim(
        UUID workId,
        UUID tenantId,
        UUID attemptId,
        UUID paymentId,
        int attemptSequence,
        ProviderWorkType workType,
        String providerId,
        String providerIdempotencyKey,
        String requestIntentHash,
        String commandPayload,
        UUID correlationId,
        UUID causationId,
        String traceparent,
        String tracestate,
        UUID leaseToken,
        Instant leaseExpiresAt,
        boolean preTransmissionRetryAvailable,
        boolean recoveryOnly,
        boolean exhausted
) {
}
