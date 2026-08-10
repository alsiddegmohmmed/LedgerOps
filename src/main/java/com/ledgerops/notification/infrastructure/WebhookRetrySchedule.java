package com.ledgerops.notification.infrastructure;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.UUID;

final class WebhookRetrySchedule {

    private static final long[] BASE_SECONDS = {1, 5, 30, 120};

    private WebhookRetrySchedule() {
    }

    static Duration delay(UUID deliveryId, int attemptNumber) {
        int index = Math.max(0, Math.min(BASE_SECONDS.length - 1, attemptNumber - 1));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(ByteBuffer.allocate(16)
                    .putLong(deliveryId.getMostSignificantBits())
                    .putLong(deliveryId.getLeastSignificantBits()).array());
            digest.update((byte) attemptNumber);
            int basisPoints = 8000 + Math.floorMod(ByteBuffer.wrap(digest.digest()).getInt(), 4001);
            return Duration.ofMillis(BASE_SECONDS[index] * 1000L * basisPoints / 10_000L);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
