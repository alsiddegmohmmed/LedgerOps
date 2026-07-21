package com.ledgerops.tenancy.application;

import com.ledgerops.tenancy.api.TenantActivityQuery;
import com.ledgerops.tenancy.api.TenantActivityStatus;
import com.ledgerops.tenancy.api.TenantReference;
import com.ledgerops.tenancy.domain.Tenant;
import com.ledgerops.tenancy.domain.TenantId;
import com.ledgerops.tenancy.domain.TenantRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
class TenantActivityQueryService implements TenantActivityQuery {

    private final TenantRepository tenantRepository;

    TenantActivityQueryService(TenantRepository tenantRepository) {
        this.tenantRepository = tenantRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public TenantActivityStatus evaluate(TenantReference tenantReference) {
        return tenantRepository.findById(TenantId.from(tenantReference.value()))
                .map(TenantActivityQueryService::statusOf)
                .orElse(TenantActivityStatus.NOT_FOUND);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public TenantActivityStatus evaluateForUpdate(TenantReference tenantReference) {
        return tenantRepository.findByIdForUpdate(TenantId.from(tenantReference.value()))
                .map(TenantActivityQueryService::statusOf)
                .orElse(TenantActivityStatus.NOT_FOUND);
    }

    private static TenantActivityStatus statusOf(Tenant tenant) {
        return tenant.canCreateOperationalActivity()
                ? TenantActivityStatus.ALLOWED
                : TenantActivityStatus.INACTIVE;
    }
}
