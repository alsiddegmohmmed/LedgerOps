package com.ledgerops.notification.infrastructure;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class WebhookDeliveryWorkerTests {

    @Test
    void responseBodyIsBoundedToSixtyFourKiB() throws Exception {
        byte[] response = new byte[64 * 1024 + 17];
        Arrays.fill(response, (byte) 'x');

        byte[] bounded = WebhookDeliveryWorker.readBounded(new ByteArrayInputStream(response));

        assertEquals(64 * 1024, bounded.length);
        assertArrayEquals(
                Arrays.copyOf(response, 64 * 1024),
                bounded);
    }

    @Test
    void responseBodyPreservesUtf8BytesWithinLimit() throws Exception {
        byte[] response = "café webhook response".getBytes(StandardCharsets.UTF_8);

        byte[] bounded = WebhookDeliveryWorker.readBounded(new ByteArrayInputStream(response));

        assertArrayEquals(response, bounded);
    }
}
