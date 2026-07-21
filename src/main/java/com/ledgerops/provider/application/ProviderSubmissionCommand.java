package com.ledgerops.provider.application;

import java.util.UUID;

public record ProviderSubmissionCommand(
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
}
