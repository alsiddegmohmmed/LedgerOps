package com.ledgerops.identity.domain;

public record TenantActivationFacts(
        boolean initialTenantAdminActive,
        boolean onboardingConsistent
) {
}
