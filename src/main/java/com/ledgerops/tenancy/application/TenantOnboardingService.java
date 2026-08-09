package com.ledgerops.tenancy.application;

import com.ledgerops.messaging.api.MessageOutbox;
import com.ledgerops.tenancy.api.TenantOnboardingPort;
import com.ledgerops.tenancy.api.TenantOnboardingRequest;
import com.ledgerops.tenancy.api.TenantReference;
import com.ledgerops.tenancy.domain.Tenant;
import com.ledgerops.tenancy.domain.TenantId;
import com.ledgerops.tenancy.domain.TenantRepository;
import com.ledgerops.tenancy.domain.TenantStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

@Service("tenantAggregateOnboardingService")
class TenantOnboardingService implements TenantOnboardingPort {

    private final TenantRepository tenants;
    private final MessageOutbox outbox;
    private final Clock clock;

    TenantOnboardingService(
            TenantRepository tenants,
            MessageOutbox outbox,
            Clock clock
    ) {
        this.tenants = tenants;
        this.outbox = outbox;
        this.clock = clock;
    }

    @Override
    @Transactional
    public TenantReference createPendingTenant(TenantOnboardingRequest request) {
        String normalizedName = request.name().trim();
        if (tenants.existsByName(normalizedName)) {
            throw new DuplicateTenantNameException(normalizedName);
        }

        Tenant tenant = new Tenant(
                TenantId.newId(),
                request.name(),
                request.defaultCurrency(),
                request.defaultLocale(),
                TenantStatus.PENDING_ACTIVATION
        );
        Tenant saved = tenants.save(tenant);
        Instant occurredAt = clock.instant();
        outbox.appendOrGet(TenantLifecycleOutboxFactory.created(
                saved.id().value(),
                request.correlationId(),
                request.operationId(),
                occurredAt
        ));
        return TenantReference.from(saved.id().value());
    }
}
