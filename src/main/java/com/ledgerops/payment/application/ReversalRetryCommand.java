package com.ledgerops.payment.application;

import com.ledgerops.identity.api.AuthenticatedPrincipal;
import com.ledgerops.identity.api.AuthorizedRequestContext;

import java.util.Objects;
import java.util.UUID;

public record ReversalRetryCommand(
        UUID tenantId,
        UUID paymentId,
        UUID reversalId,
        UUID previousAttemptId,
        UUID providerEvidenceId,
        String providerId,
        boolean confirmation,
        String reason,
        AuthorizedRequestContext authorization,
        AuthenticatedPrincipal actor
) {
    public ReversalRetryCommand {
        Objects.requireNonNull(tenantId, "Tenant ID must not be null");
        Objects.requireNonNull(paymentId, "Payment ID must not be null");
        Objects.requireNonNull(reversalId, "Reversal ID must not be null");
        Objects.requireNonNull(previousAttemptId, "Previous attempt ID must not be null");
        Objects.requireNonNull(providerEvidenceId, "Provider evidence ID must not be null");
        if (providerId == null || providerId.isBlank()) {
            throw new IllegalArgumentException("Provider ID must not be blank");
        }
        Objects.requireNonNull(authorization, "Authorization context must not be null");
        Objects.requireNonNull(actor, "Actor must not be null");
    }
}
