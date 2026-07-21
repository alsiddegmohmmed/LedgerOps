package com.ledgerops.provider.application;

import java.time.Instant;
import java.util.UUID;

public record ProviderWorkClaim(
        UUID workId,
        UUID tenantId,
        UUID attemptId,
        UUID paymentId,
        ProviderWorkType workType,
        String providerId,
        String providerIdempotencyKey,
        String requestIntentHash,
        String commandPayload,
        UUID correlationId,
        UUID causationId,
        UUID leaseToken,
        Instant leaseExpiresAt,
        boolean recoveryOnly,
        boolean exhausted
) {
}
