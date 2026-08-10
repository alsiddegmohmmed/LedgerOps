package com.ledgerops.payment.api;

import java.time.Instant;
import java.util.UUID;

public record PaymentAttemptSnapshot(
        UUID attemptId,
        int sequence,
        String providerId,
        String providerIdempotencyKey,
        Instant initiatedAt
) {
}
