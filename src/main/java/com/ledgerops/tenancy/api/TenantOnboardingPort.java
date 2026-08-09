package com.ledgerops.tenancy.api;

public interface TenantOnboardingPort {

    TenantReference createPendingTenant(TenantOnboardingRequest request);
}
