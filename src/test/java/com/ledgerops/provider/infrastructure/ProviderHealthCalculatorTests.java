package com.ledgerops.provider.infrastructure;

import com.ledgerops.provider.api.ProviderHealthPolicy;
import com.ledgerops.provider.api.ProviderHealthState;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProviderHealthCalculatorTests {

    private final ProviderHealthPolicy policy = ProviderHealthPolicy.seeded(
            "SIMULATOR", Instant.parse("2026-08-10T00:00:00Z"));

    @Test
    void appliesTheApprovedStatePrecedenceAndMinimumSampleBoundary() {
        assertEquals(ProviderHealthState.UNKNOWN,
                state(9, 9, 0, 0, 0, "CLOSED"));
        assertEquals(ProviderHealthState.UNAVAILABLE,
                state(10, 0, 0, 0, 10, "CLOSED"));
        assertEquals(ProviderHealthState.UNAVAILABLE,
                state(10, 10, 0, 0, 10, "OPEN"));
        assertEquals(ProviderHealthState.DEGRADED,
                state(10, 8, 2, 0, 10, "CLOSED"));
        assertEquals(ProviderHealthState.DEGRADED,
                state(10, 10, 0, 0, 3_000, "CLOSED"));
        assertEquals(ProviderHealthState.DEGRADED,
                state(10, 10, 0, 0, 0, "HALF_OPEN"));
        assertEquals(ProviderHealthState.HEALTHY,
                state(10, 9, 1, 0, 2_999, "CLOSED"));
    }

    private ProviderHealthState state(
            int completed,
            int successful,
            int timeouts,
            int systemErrors,
            long p95,
            String circuit
    ) {
        return ProviderHealthCalculator.state(
                policy, completed, successful, timeouts, systemErrors, p95, circuit);
    }
}
