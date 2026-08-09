package com.ledgerops.tenancy.application;

import com.ledgerops.audit.api.AuditAppendPort;
import com.ledgerops.messaging.api.MessageOutbox;
import com.ledgerops.tenancy.api.TenantLifecyclePort;
import com.ledgerops.tenancy.api.TenantLifecycleRequest;
import com.ledgerops.tenancy.api.TenantReference;
import com.ledgerops.tenancy.domain.Tenant;
import com.ledgerops.tenancy.domain.TenantId;
import com.ledgerops.tenancy.domain.TenantRepository;
import com.ledgerops.tenancy.domain.TenantStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.function.UnaryOperator;

@Service("tenantLifecycleService")
class TenantLifecycleService implements TenantLifecyclePort {

    private final TenantRepository tenants;
    private final MessageOutbox outbox;
    private final AuditAppendPort audit;
    private final Clock clock;

    TenantLifecycleService(
            TenantRepository tenants,
            MessageOutbox outbox,
            AuditAppendPort audit,
            Clock clock
    ) {
        this.tenants = tenants;
        this.outbox = outbox;
        this.audit = audit;
        this.clock = clock;
    }

    @Override
    @Transactional
    public TenantReference suspend(TenantLifecycleRequest request) {
        return transition(request, TenantStatus.SUSPENDED, Tenant::suspend);
    }

    @Override
    @Transactional
    public TenantReference archive(TenantLifecycleRequest request) {
        return transition(request, TenantStatus.ARCHIVED, Tenant::archive);
    }

    private TenantReference transition(
            TenantLifecycleRequest request,
            TenantStatus targetStatus,
            UnaryOperator<Tenant> transition
    ) {
        TenantId tenantId = TenantId.from(request.tenant().value());
        Tenant current = tenants.findByIdForUpdate(tenantId)
                .orElseThrow(() -> new TenantNotFoundException(tenantId));
        TenantStatus previousStatus = current.status();

        Tenant changed;
        try {
            changed = transition.apply(current);
        } catch (IllegalStateException exception) {
            throw new TenantLifecycleException(tenantId, targetStatus, exception);
        }

        Tenant saved = tenants.save(changed);
        Instant occurredAt = clock.instant();
        outbox.appendOrGet(TenantLifecycleOutboxFactory.changed(
                saved.id().value(),
                previousStatus.name(),
                saved.status().name(),
                saved.version(),
                request.correlationId(),
                request.operationId(),
                occurredAt
        ));
        audit.appendTenantLifecycleChanged(
                request.actorIssuer(),
                request.actorSubject(),
                saved.id().value(),
                previousStatus.name(),
                saved.status().name(),
                request.correlationId().toString()
        );
        return TenantReference.from(saved.id().value());
    }
}
