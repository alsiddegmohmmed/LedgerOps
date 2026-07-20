package com.ledgerops.tenancy.application;

import com.ledgerops.tenancy.domain.Tenant;
import com.ledgerops.tenancy.domain.TenantId;
import com.ledgerops.tenancy.domain.TenantRepository;
import com.ledgerops.tenancy.domain.TenantStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.function.UnaryOperator;

@Service
public class TenantManagementService {

    private static final Logger LOGGER = LoggerFactory.getLogger(
            TenantManagementService.class
    );

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

        Tenant saved = tenantRepository.save(tenant);
        LOGGER.info(
                "Tenant created tenantId={} status={} defaultCurrency={} defaultLocale={}",
                saved.id().value(),
                saved.status(),
                saved.defaultCurrency().getCurrencyCode(),
                saved.defaultLocale().toLanguageTag()
        );
        return saved;
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

        Tenant saved = tenantRepository.save(transitionedTenant);
        LOGGER.info(
                "Tenant status changed tenantId={} previousStatus={} status={}",
                saved.id().value(),
                tenant.status(),
                saved.status()
        );
        return saved;
    }

    private Tenant findTenant(TenantId tenantId) {
        return tenantRepository.findById(tenantId)
                .orElseThrow(() -> new TenantNotFoundException(tenantId));
    }
}
