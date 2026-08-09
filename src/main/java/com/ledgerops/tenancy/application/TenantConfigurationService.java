package com.ledgerops.tenancy.application;

import com.ledgerops.audit.api.AuditAppendPort;
import com.ledgerops.identity.api.AuthenticatedPrincipal;
import com.ledgerops.identity.api.AuthorizationPermissionDeniedException;
import com.ledgerops.identity.api.AuthorizationResourceNotFoundException;
import com.ledgerops.identity.api.AuthorizedRequestContext;
import com.ledgerops.tenancy.api.TenantReference;
import com.ledgerops.tenancy.domain.Tenant;
import com.ledgerops.tenancy.domain.TenantConfiguration;
import com.ledgerops.tenancy.domain.TenantConfigurationRepository;
import com.ledgerops.tenancy.domain.TenantId;
import com.ledgerops.tenancy.domain.TenantRepository;
import com.ledgerops.tenancy.domain.TenantStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

@Service
public class TenantConfigurationService {

    private final TenantRepository tenants;
    private final TenantConfigurationRepository configurations;
    private final AuditAppendPort audit;
    private final Clock clock;

    public TenantConfigurationService(
            TenantRepository tenants,
            TenantConfigurationRepository configurations,
            AuditAppendPort audit,
            Clock clock
    ) {
        this.tenants = tenants;
        this.configurations = configurations;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public TenantConfiguration update(TenantConfigurationCommand command) {
        TenantId tenantId = authorize(command.tenant(), command.context());
        Tenant tenant = tenants.findByIdForUpdate(tenantId)
                .orElseThrow(() -> new TenantNotFoundException(tenantId));
        requireConfigurable(tenant);

        long version = configurations.nextVersion(tenantId);
        TenantConfiguration configuration = new TenantConfiguration(
                tenantId,
                version,
                command.allowedCurrencies(),
                command.defaultLocale(),
                command.timezone(),
                command.displaySettingsJson(),
                clock.instant(),
                actorIdentity(command.actor())
        );
        configurations.append(configuration);
        audit.appendTenantConfigurationChanged(
                command.actor().issuer(),
                command.actor().subject(),
                tenantId.value(),
                version,
                command.context().correlationId()
        );
        return configuration;
    }

    private TenantId authorize(TenantReference tenant, AuthorizedRequestContext context) {
        if (!context.tenantId().equals(tenant.value())) {
            throw new AuthorizationResourceNotFoundException();
        }
        if (!context.isHuman() || !context.canConfigureTenant()) {
            throw new AuthorizationPermissionDeniedException("tenant:configure");
        }
        return TenantId.from(tenant.value());
    }

    private void requireConfigurable(Tenant tenant) {
        if (tenant.status() != TenantStatus.PENDING_ACTIVATION
                && tenant.status() != TenantStatus.ACTIVE) {
            throw new IllegalStateException(
                    "Tenant configuration cannot change while Tenant is " + tenant.status());
        }
    }

    private String actorIdentity(AuthenticatedPrincipal actor) {
        return actor.issuer() + "|" + actor.subject();
    }
}
