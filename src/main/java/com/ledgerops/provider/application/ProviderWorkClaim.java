package com.ledgerops.provider.application;

import com.ledgerops.provider.api.ProviderOperationType;

import java.time.Instant;
import java.util.UUID;

public record ProviderWorkClaim(
        UUID workId,
        UUID tenantId,
        UUID attemptId,
        UUID paymentId,
        ProviderOperationType operationType,
        UUID operationId,
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

    public ProviderWorkClaim(
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
        this(workId, tenantId, attemptId, paymentId, ProviderOperationType.PAYMENT, paymentId,
                attemptSequence, workType, providerId, providerIdempotencyKey,
                requestIntentHash, commandPayload, correlationId, causationId,
                traceparent, tracestate, leaseToken, leaseExpiresAt,
                preTransmissionRetryAvailable, recoveryOnly, exhausted);
    }
}
