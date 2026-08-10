package com.ledgerops.provider.application;

import com.ledgerops.provider.api.ProviderOperationType;

import java.util.UUID;

public record ProviderSubmissionCommand(
        UUID tenantId,
        UUID messageId,
        UUID attemptId,
        UUID paymentId,
        ProviderOperationType operationType,
        UUID operationId,
        int attemptSequence,
        String providerId,
        String providerIdempotencyKey,
        String requestIntentHash,
        String canonicalPayload,
        UUID correlationId,
        UUID causationId,
        String traceparent,
        String tracestate
) {

    public ProviderSubmissionCommand(
            UUID tenantId,
            UUID messageId,
            UUID attemptId,
            UUID paymentId,
            int attemptSequence,
            String providerId,
            String providerIdempotencyKey,
            String requestIntentHash,
            String canonicalPayload,
            UUID correlationId,
            UUID causationId,
            String traceparent,
            String tracestate
    ) {
        this(tenantId, messageId, attemptId, paymentId, ProviderOperationType.PAYMENT, paymentId,
                attemptSequence, providerId, providerIdempotencyKey, requestIntentHash,
                canonicalPayload, correlationId, causationId, traceparent, tracestate);
    }
}
