package com.ledgerops.payment.application;

import com.ledgerops.provider.api.ProviderResultCategory;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record AcceptedFinalProviderResult(
        UUID tenantId,
        UUID paymentId,
        UUID attemptId,
        UUID providerEvidenceId,
        UUID providerResultId,
        ProviderResultCategory finalCategory,
        String providerReference,
        Instant appliedAt
) {
    public AcceptedFinalProviderResult {
        Objects.requireNonNull(tenantId, "Tenant ID must not be null");
        Objects.requireNonNull(paymentId, "Payment ID must not be null");
        Objects.requireNonNull(attemptId, "Attempt ID must not be null");
        Objects.requireNonNull(providerEvidenceId, "Provider evidence ID must not be null");
        Objects.requireNonNull(providerResultId, "Provider result ID must not be null");
        Objects.requireNonNull(finalCategory, "Final category must not be null");
        if (finalCategory != ProviderResultCategory.SUCCESS
                && finalCategory != ProviderResultCategory.DECLINED
                && finalCategory != ProviderResultCategory.PERMANENT_FAILURE) {
            throw new IllegalArgumentException("Accepted Provider result must be final");
        }
        Objects.requireNonNull(appliedAt, "Applied-at time must not be null");
    }
}
