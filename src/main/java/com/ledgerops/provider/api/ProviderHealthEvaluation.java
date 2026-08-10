package com.ledgerops.provider.api;

import java.time.Instant;
import java.util.UUID;

public record ProviderHealthEvaluation(
        UUID evaluationId,
        String providerId,
        UUID policyId,
        long policyVersion,
        long healthVersion,
        ProviderHealthState state,
        int completedCalls,
        int successfulCommunications,
        int timeoutCount,
        int systemErrorCount,
        long p95LatencyMillis,
        String circuitState,
        Instant windowStartedAt,
        Instant windowEndedAt,
        Instant evaluatedAt
) {
}
