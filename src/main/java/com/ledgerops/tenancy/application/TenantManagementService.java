package com.ledgerops.tenancy.application;

import com.ledgerops.tenancy.domain.Tenant;
import com.ledgerops.tenancy.domain.TenantId;
import com.ledgerops.tenancy.domain.TenantRepository;
import com.ledgerops.tenancy.domain.TenantStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.function.UnaryOperator;

@Service
public class TenantManagementService {

    private final TenantRepository tenantRepository;

    public TenantManagementService(TenantRepository tenantRepository) {
        this.tenantRepository = tenantRepository;
    }

    @Transactional
    public Tenant createTenant(CreateTenantCommand command) {
        String normalizedName = command.name().trim();

        if (tenantRepository.existsByName(normalizedName)) {
            throw new DuplicateTenantNameException(normalizedName);
        }

        Tenant tenant = new Tenant(
                TenantId.newId(),
                command.name(),
                command.defaultCurrency(),
                command.defaultLocale(),
                TenantStatus.PENDING_ACTIVATION
        );

        return tenantRepository.save(tenant);
    }

    @Transactional(readOnly = true)
    public Tenant getTenant(TenantId tenantId) {
        return findTenant(tenantId);
    }

    @Transactional
    public Tenant activateTenant(TenantId tenantId) {
        return transition(tenantId, TenantStatus.ACTIVE, Tenant::activate);
    }

    @Transactional
    public Tenant suspendTenant(TenantId tenantId) {
        return transition(tenantId, TenantStatus.SUSPENDED, Tenant::suspend);
    }

    @Transactional
    public Tenant archiveTenant(TenantId tenantId) {
        return transition(tenantId, TenantStatus.ARCHIVED, Tenant::archive);
    }

    private Tenant transition(
            TenantId tenantId,
            TenantStatus targetStatus,
            UnaryOperator<Tenant> transition
    ) {
        Tenant tenant = findTenant(tenantId);
        Tenant transitionedTenant;

        try {
            transitionedTenant = transition.apply(tenant);
        } catch (IllegalStateException exception) {
            throw new TenantLifecycleException(tenantId, targetStatus, exception);
        }

        return tenantRepository.save(transitionedTenant);
    }

    private Tenant findTenant(TenantId tenantId) {
        return tenantRepository.findById(tenantId)
                .orElseThrow(() -> new TenantNotFoundException(tenantId));
    }
}
