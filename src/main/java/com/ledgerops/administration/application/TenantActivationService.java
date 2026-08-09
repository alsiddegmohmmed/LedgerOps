package com.ledgerops.administration.application;

import com.ledgerops.administration.api.TenantActivationCommand;
import com.ledgerops.administration.api.TenantActivationPort;
import com.ledgerops.administration.api.TenantActivationResult;
import com.ledgerops.identity.api.PlatformAuthorityPort;
import com.ledgerops.identity.api.TenantActivationReadPort;
import com.ledgerops.identity.api.TenantActivationReadiness;
import com.ledgerops.merchant.api.MerchantActivationReadPort;
import com.ledgerops.tenancy.api.TenantActivationRequest;
import com.ledgerops.tenancy.api.TenantReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service("administrationTenantActivationService")
class TenantActivationService implements TenantActivationPort {

    private final PlatformAuthorityPort platformAuthority;
    private final com.ledgerops.tenancy.api.TenantActivationPort tenancy;
    private final TenantActivationReadPort identity;
    private final MerchantActivationReadPort merchants;

    TenantActivationService(
            PlatformAuthorityPort platformAuthority,
            com.ledgerops.tenancy.api.TenantActivationPort tenancy,
            TenantActivationReadPort identity,
            MerchantActivationReadPort merchants
    ) {
        this.platformAuthority = platformAuthority;
        this.tenancy = tenancy;
        this.identity = identity;
        this.merchants = merchants;
    }

    @Transactional
    public TenantActivationResult activate(TenantActivationCommand command) {
        platformAuthority.requirePlatformAdmin(command.actor());
        TenantReference tenant = command.tenant();

        tenancy.lockForActivation(tenant);
        TenantActivationReadiness identityReadiness = identity.assess(tenant.value());
        boolean activeMerchantExists = merchants.hasActiveMerchant(tenant);
        if (!identityReadiness.initialTenantAdminActive()
                || !identityReadiness.onboardingConsistent()
                || !activeMerchantExists) {
            throw new TenantActivationPrerequisitesException(
                    tenant, identityReadiness, activeMerchantExists);
        }

        return new TenantActivationResult(tenancy.activate(
                new TenantActivationRequest(
                        tenant,
                        identityReadiness.initialTenantAdminActive(),
                        activeMerchantExists,
                        identityReadiness.onboardingConsistent(),
                        command.actor().issuer(),
                        command.actor().subject(),
                        command.correlationId(),
                        command.operationId()
                )
        ));
    }
}
