package com.ledgerops.payment.application;

import com.ledgerops.identity.api.AuthenticatedPrincipal;
import com.ledgerops.identity.api.AuthorizedRequestContext;

import java.util.Objects;
import java.util.UUID;

public record ReversalRequestCommand(
        UUID tenantId,
        UUID paymentId,
        boolean confirmation,
        String reason,
        AuthorizedRequestContext authorization,
        AuthenticatedPrincipal actor
) {
    public ReversalRequestCommand {
        Objects.requireNonNull(tenantId, "Tenant ID must not be null");
        Objects.requireNonNull(paymentId, "Payment ID must not be null");
        Objects.requireNonNull(authorization, "Authorization context must not be null");
        Objects.requireNonNull(actor, "Actor must not be null");
    }
}
