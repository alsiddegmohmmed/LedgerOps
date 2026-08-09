package com.ledgerops.identity.api;

public record TenantActivationReadiness(
        boolean initialTenantAdminActive,
        boolean onboardingConsistent
) {
}
