package com.ledgerops.administration.application;

import com.ledgerops.identity.api.TenantActivationReadiness;
import com.ledgerops.tenancy.api.TenantReference;

public final class TenantActivationPrerequisitesException extends RuntimeException {

    private final TenantReference tenant;
    private final TenantActivationReadiness identityReadiness;
    private final boolean activeMerchantExists;

    TenantActivationPrerequisitesException(
            TenantReference tenant,
            TenantActivationReadiness identityReadiness,
            boolean activeMerchantExists
    ) {
        super("Tenant activation prerequisites are not satisfied");
        this.tenant = tenant;
        this.identityReadiness = identityReadiness;
        this.activeMerchantExists = activeMerchantExists;
    }

    public TenantReference tenant() {
        return tenant;
    }

    public TenantActivationReadiness identityReadiness() {
        return identityReadiness;
    }

    public boolean activeMerchantExists() {
        return activeMerchantExists;
    }
}
