package com.ledgerops.notification.infrastructure;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebhookRetryScheduleTests {

    @Test
    void usesDeterministicApprovedDelaysAndTwentyPercentJitterBounds() {
        UUID deliveryId = UUID.fromString("018f47f2-bd77-7d62-a90e-8e276d5608e8");

        assertEquals(WebhookRetrySchedule.delay(deliveryId, 3),
                WebhookRetrySchedule.delay(deliveryId, 3));
        assertWithin(WebhookRetrySchedule.delay(deliveryId, 1), 800, 1_200);
        assertWithin(WebhookRetrySchedule.delay(deliveryId, 2), 4_000, 6_000);
        assertWithin(WebhookRetrySchedule.delay(deliveryId, 3), 24_000, 36_000);
        assertWithin(WebhookRetrySchedule.delay(deliveryId, 4), 96_000, 144_000);
    }

    private void assertWithin(Duration duration, long minimumMillis, long maximumMillis) {
        assertTrue(duration.toMillis() >= minimumMillis);
        assertTrue(duration.toMillis() <= maximumMillis);
    }
}
