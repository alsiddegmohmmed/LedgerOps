package com.ledgerops.provider.api;

import java.time.Instant;
import java.util.UUID;

public record ProviderEvidence(
        UUID evidenceId,
        UUID tenantId,
        UUID paymentId,
        UUID attemptId,
        String providerId,
        String providerIdempotencyKey,
        UUID providerResultId,
        String providerReference,
        ProviderResultCategory category,
        RetryDisposition retryDisposition,
        boolean providerTransactionFound,
        boolean noAcceptanceProven,
        String evidenceOrigin,
        Instant observedAt
) {
}
