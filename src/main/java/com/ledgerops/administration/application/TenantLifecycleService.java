package com.ledgerops.administration.application;

import com.ledgerops.administration.api.TenantLifecycleCommand;
import com.ledgerops.administration.api.TenantLifecyclePort;
import com.ledgerops.administration.api.TenantLifecycleResult;
import com.ledgerops.identity.api.PlatformAuthorityPort;
import com.ledgerops.tenancy.api.TenantLifecycleRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service("administrationTenantLifecycleService")
class TenantLifecycleService implements TenantLifecyclePort {

    private final PlatformAuthorityPort platformAuthority;
    private final com.ledgerops.tenancy.api.TenantLifecyclePort tenancy;

    TenantLifecycleService(
            PlatformAuthorityPort platformAuthority,
            com.ledgerops.tenancy.api.TenantLifecyclePort tenancy
    ) {
        this.platformAuthority = platformAuthority;
        this.tenancy = tenancy;
    }

    @Override
    @Transactional
    public TenantLifecycleResult suspend(TenantLifecycleCommand command) {
        platformAuthority.requirePlatformAdmin(command.actor());
        return new TenantLifecycleResult(
                tenancy.suspend(request(command)),
                "SUSPENDED"
        );
    }

    @Override
    @Transactional
    public TenantLifecycleResult archive(TenantLifecycleCommand command) {
        platformAuthority.requirePlatformAdmin(command.actor());
        return new TenantLifecycleResult(
                tenancy.archive(request(command)),
                "ARCHIVED"
        );
    }

    private TenantLifecycleRequest request(TenantLifecycleCommand command) {
        return new TenantLifecycleRequest(
                command.tenant(),
                command.actor().issuer(),
                command.actor().subject(),
                command.correlationId(),
                command.operationId()
        );
    }
}
