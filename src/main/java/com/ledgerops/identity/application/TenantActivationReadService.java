package com.ledgerops.identity.application;

import com.ledgerops.identity.api.TenantActivationReadPort;
import com.ledgerops.identity.api.TenantActivationReadiness;
import com.ledgerops.identity.domain.TenantActivationFacts;
import com.ledgerops.identity.domain.TenantActivationReadRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
class TenantActivationReadService implements TenantActivationReadPort {

    private final TenantActivationReadRepository repository;

    TenantActivationReadService(TenantActivationReadRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional(readOnly = true)
    public TenantActivationReadiness assess(UUID tenantId) {
        TenantActivationFacts facts = repository.assess(tenantId);
        return new TenantActivationReadiness(
                facts.initialTenantAdminActive(),
                facts.onboardingConsistent()
        );
    }
}
