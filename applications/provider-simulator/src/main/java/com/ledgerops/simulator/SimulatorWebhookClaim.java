package com.ledgerops.simulator;

import java.time.Instant;
import java.util.UUID;

record SimulatorWebhookClaim(
        UUID deliveryId,
        UUID providerEventId,
        String payload,
        String signatureMode,
        int repeatRemaining,
        int attemptCount,
        UUID leaseToken,
        Instant leaseExpiresAt
) {
}
