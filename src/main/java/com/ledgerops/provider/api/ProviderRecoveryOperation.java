package com.ledgerops.provider.api;

import java.time.Instant;
import java.util.UUID;

public record ProviderRecoveryOperation(
        UUID evidenceId,
        UUID workId,
        UUID attemptId,
        UUID retryRequestId,
        String workStatus,
        String resultCategory,
        String retryDisposition,
        boolean providerTransactionFound,
        boolean noAcceptanceProven,
        Instant dueAt,
        Instant requestedAt,
        Instant observedAt
) {
}
