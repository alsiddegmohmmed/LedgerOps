package com.ledgerops.identity.api;

public interface IdentityOnboardingPort {

    IdentityOnboardingResult createInitialTenantAdminInvitation(
            InitialTenantAdminInvitationRequest request
    );
}
