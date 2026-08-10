package com.ledgerops.provider.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record ProviderScenarioAssignment(
        UUID assignmentId,
        ProviderScenarioScope scope,
        UUID tenantId,
        UUID paymentId,
        UUID profileId,
        long profileVersion,
        boolean active,
        Instant createdAt
) {

    public ProviderScenarioAssignment {
        Objects.requireNonNull(assignmentId, "Assignment ID must not be null");
        Objects.requireNonNull(scope, "Scenario scope must not be null");
        Objects.requireNonNull(profileId, "Profile ID must not be null");
        Objects.requireNonNull(createdAt, "Assignment creation time must not be null");
        if (profileVersion < 1) throw new IllegalArgumentException("Profile version must be positive");
        if (scope == ProviderScenarioScope.GLOBAL && (tenantId != null || paymentId != null)) {
            throw new IllegalArgumentException("Global assignment cannot have a Tenant or Payment target");
        }
        if (scope == ProviderScenarioScope.TENANT && (tenantId == null || paymentId != null)) {
            throw new IllegalArgumentException("Tenant assignment requires only a Tenant target");
        }
        if (scope == ProviderScenarioScope.PAYMENT && (tenantId == null || paymentId == null)) {
            throw new IllegalArgumentException("Payment assignment requires Tenant and Payment targets");
        }
    }
}
