package com.ledgerops.payment.application;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ReversalRetryApplication(
        UUID tenantId,
        UUID reversalId,
        UUID paymentId,
        UUID previousAttemptId,
        UUID newAttemptId,
        UUID providerEvidenceId,
        String providerId,
        String requestReason,
        Instant requestedAt,
        Instant appliedAt
) {
    public ReversalRetryApplication {
        Objects.requireNonNull(tenantId, "Tenant ID must not be null");
        Objects.requireNonNull(reversalId, "Reversal ID must not be null");
        Objects.requireNonNull(paymentId, "Payment ID must not be null");
        Objects.requireNonNull(previousAttemptId, "Previous attempt ID must not be null");
        Objects.requireNonNull(newAttemptId, "New attempt ID must not be null");
        Objects.requireNonNull(providerEvidenceId, "Provider evidence ID must not be null");
        if (providerId == null || providerId.isBlank()) {
            throw new IllegalArgumentException("Provider ID must not be blank");
        }
        if (requestReason == null || requestReason.isBlank()) {
            throw new IllegalArgumentException("Retry reason must not be blank");
        }
        Objects.requireNonNull(requestedAt, "Requested-at time must not be null");
        Objects.requireNonNull(appliedAt, "Applied-at time must not be null");
    }
}
