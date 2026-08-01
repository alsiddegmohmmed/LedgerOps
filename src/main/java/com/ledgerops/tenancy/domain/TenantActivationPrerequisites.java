package com.ledgerops.tenancy.domain;

public record TenantActivationPrerequisites(
        boolean initialTenantAdminActive,
        boolean activeMerchantExists,
        boolean onboardingConsistent
) {
    public boolean satisfied() {
        return initialTenantAdminActive && activeMerchantExists && onboardingConsistent;
    }
}
