package com.ledgerops.provider.infrastructure;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.UUID;

final class ProviderSchedule {
    private ProviderSchedule() {
    }

    static Duration retryDelay(UUID workId, int nextAttemptSequence) {
        long seconds = Math.min(60, 5L << Math.max(0, nextAttemptSequence - 2));
        return jitter(Duration.ofSeconds(seconds), workId, nextAttemptSequence);
    }

    static Duration statusDelay(UUID workId, int queryOrdinal) {
        long seconds = Math.min(300, 10L << Math.min(30, Math.max(0, queryOrdinal - 1)));
        return jitter(Duration.ofSeconds(seconds), workId, queryOrdinal);
    }

    static Duration preTransmissionDelay(UUID workId) {
        return jitter(Duration.ofMillis(200), workId, 1);
    }

    private static Duration jitter(Duration base, UUID identity, int ordinal) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(ByteBuffer.allocate(16)
                    .putLong(identity.getMostSignificantBits())
                    .putLong(identity.getLeastSignificantBits()).array());
            digest.update(ByteBuffer.allocate(4).putInt(ordinal).array());
            int basisPoints = 8000 + Math.floorMod(ByteBuffer.wrap(digest.digest()).getInt(), 4001);
            long millis = Math.multiplyExact(base.toMillis(), basisPoints) / 10_000L;
            return Duration.ofMillis(millis);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
