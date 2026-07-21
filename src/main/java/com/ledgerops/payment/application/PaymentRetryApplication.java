package com.ledgerops.payment.application;

import java.time.Instant;
import java.util.UUID;

public record PaymentRetryApplication(
        UUID tenantId,
        UUID retryRequestId,
        UUID paymentId,
        UUID previousAttemptId,
        UUID newAttemptId,
        UUID providerEvidenceId,
        String providerId,
        Instant requestedAt,
        Instant appliedAt
) {
}
