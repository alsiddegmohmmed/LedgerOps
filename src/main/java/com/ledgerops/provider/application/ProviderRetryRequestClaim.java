package com.ledgerops.provider.application;

import java.time.Instant;
import java.util.UUID;

public record ProviderRetryRequestClaim(
        UUID workId,
        UUID tenantId,
        UUID paymentId,
        UUID previousAttemptId,
        int attemptSequence,
        UUID providerEvidenceId,
        UUID providerResultId,
        String providerId,
        UUID correlationId,
        UUID leaseToken,
        Instant leaseExpiresAt
) {
}
