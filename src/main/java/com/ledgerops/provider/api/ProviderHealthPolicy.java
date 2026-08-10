package com.ledgerops.provider.api;

import java.time.Instant;
import java.util.UUID;

public record ProviderHealthPolicy(
        UUID policyId,
        String providerId,
        long version,
        int windowSeconds,
        int evaluationIntervalSeconds,
        int minimumCompletedCalls,
        double degradedErrorRate,
        long degradedP95LatencyMillis,
        boolean active,
        Instant createdAt
) {
    public static final UUID SEEDED_POLICY_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000027");

    public ProviderHealthPolicy {
        if (policyId == null || providerId == null || createdAt == null) {
            throw new IllegalArgumentException("Health policy identity is required");
        }
        if (version < 1 || windowSeconds <= 0 || evaluationIntervalSeconds <= 0
                || minimumCompletedCalls <= 0 || degradedErrorRate < 0 || degradedErrorRate > 1
                || degradedP95LatencyMillis <= 0) {
            throw new IllegalArgumentException("Health policy parameters are invalid");
        }
    }

    public static ProviderHealthPolicy seeded(String providerId, Instant createdAt) {
        return new ProviderHealthPolicy(
                SEEDED_POLICY_ID, providerId, 1, 300, 30, 10,
                0.20d, 3_000, true, createdAt);
    }
}
