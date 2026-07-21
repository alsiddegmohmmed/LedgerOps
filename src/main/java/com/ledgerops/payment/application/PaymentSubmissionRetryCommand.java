package com.ledgerops.payment.application;

import java.time.Instant;
import java.util.UUID;

public record PaymentSubmissionRetryCommand(
        UUID messageId,
        UUID retryRequestId,
        UUID tenantId,
        UUID paymentId,
        UUID previousAttemptId,
        UUID providerEvidenceId,
        String providerId,
        Instant requestedAt,
        UUID correlationId
) {
}
