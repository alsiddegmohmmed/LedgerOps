package com.ledgerops.provider.infrastructure;

import com.ledgerops.provider.api.ProviderHealthPolicy;
import com.ledgerops.provider.api.ProviderHealthState;

final class ProviderHealthCalculator {

    private ProviderHealthCalculator() {
    }

    static ProviderHealthState state(
            ProviderHealthPolicy policy,
            int completedCalls,
            int successfulCommunications,
            int timeoutCount,
            int systemErrorCount,
            long p95LatencyMillis,
            String circuitState
    ) {
        if (completedCalls < policy.minimumCompletedCalls()) {
            return ProviderHealthState.UNKNOWN;
        }
        if ("OPEN".equals(circuitState) || successfulCommunications == 0) {
            return ProviderHealthState.UNAVAILABLE;
        }
        double errorRate = (double) (timeoutCount + systemErrorCount) / completedCalls;
        if ("HALF_OPEN".equals(circuitState)
                || errorRate >= policy.degradedErrorRate()
                || p95LatencyMillis >= policy.degradedP95LatencyMillis()) {
            return ProviderHealthState.DEGRADED;
        }
        return ProviderHealthState.HEALTHY;
    }
}
