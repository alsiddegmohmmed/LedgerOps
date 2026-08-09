package com.ledgerops.administration.api;

public interface TenantOnboardingPort {

    TenantOnboardingResult onboard(TenantOnboardingCommand command);
}
