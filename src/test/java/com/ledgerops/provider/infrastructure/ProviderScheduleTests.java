package com.ledgerops.provider.infrastructure;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProviderScheduleTests {
    @Test
    void deterministicRetryAndStatusDelaysStayWithinApprovedJitterBounds() {
        UUID identity = UUID.fromString("018f47f2-bd77-7d62-a90e-8e276d5608e8");

        assertEquals(ProviderSchedule.retryDelay(identity, 2),
                ProviderSchedule.retryDelay(identity, 2));
        assertWithin(ProviderSchedule.retryDelay(identity, 2), 4, 6);
        assertWithin(ProviderSchedule.retryDelay(identity, 3), 8, 12);
        assertWithin(ProviderSchedule.statusDelay(identity, 1), 8, 12);
        assertWithin(ProviderSchedule.statusDelay(identity, 12), 240, 360);
        assertTrue(ProviderSchedule.preTransmissionDelay(identity).toMillis() >= 160);
        assertTrue(ProviderSchedule.preTransmissionDelay(identity).toMillis() <= 240);
    }

    private void assertWithin(Duration actual, long minimumSeconds, long maximumSeconds) {
        assertTrue(actual.compareTo(Duration.ofSeconds(minimumSeconds)) >= 0);
        assertTrue(actual.compareTo(Duration.ofSeconds(maximumSeconds)) <= 0);
    }
}
