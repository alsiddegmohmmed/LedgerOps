package com.ledgerops.payment.api;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record PaymentReconciliationPageRequest(
        UUID tenantId,
        Instant sourceCutoff,
        int limit,
        ReconciliationSubjectCursor after
) {

    public PaymentReconciliationPageRequest {
        Objects.requireNonNull(tenantId, "Tenant ID must not be null");
        Objects.requireNonNull(sourceCutoff, "Source cutoff must not be null");
        if (limit < 1 || limit > 500) {
            throw new IllegalArgumentException("Reconciliation page limit must be between 1 and 500");
        }
        if (after != null && after.appliedAt().isAfter(sourceCutoff)) {
            throw new IllegalArgumentException("A reconciliation cursor cannot be after the source cutoff");
        }
    }

    public Optional<ReconciliationSubjectCursor> afterCursor() {
        return Optional.ofNullable(after);
    }
}
