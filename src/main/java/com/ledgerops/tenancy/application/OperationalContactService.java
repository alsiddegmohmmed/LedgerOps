package com.ledgerops.tenancy.application;

import com.ledgerops.audit.api.AuditAppendPort;
import com.ledgerops.identity.api.AuthenticatedPrincipal;
import com.ledgerops.identity.api.AuthorizationPermissionDeniedException;
import com.ledgerops.identity.api.AuthorizationResourceNotFoundException;
import com.ledgerops.identity.api.AuthorizedRequestContext;
import com.ledgerops.tenancy.api.TenantReference;
import com.ledgerops.tenancy.domain.OperationalContact;
import com.ledgerops.tenancy.domain.OperationalContactRepository;
import com.ledgerops.tenancy.domain.Tenant;
import com.ledgerops.tenancy.domain.TenantId;
import com.ledgerops.tenancy.domain.TenantRepository;
import com.ledgerops.tenancy.domain.TenantStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

@Service
public class OperationalContactService {

    private final TenantRepository tenants;
    private final OperationalContactRepository contacts;
    private final AuditAppendPort audit;
    private final Clock clock;

    public OperationalContactService(
            TenantRepository tenants,
            OperationalContactRepository contacts,
            AuditAppendPort audit,
            Clock clock
    ) {
        this.tenants = tenants;
        this.contacts = contacts;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public OperationalContact update(OperationalContactCommand command) {
        TenantId tenantId = authorize(command.tenant(), command.context());
        Tenant tenant = tenants.findByIdForUpdate(tenantId)
                .orElseThrow(() -> new TenantNotFoundException(tenantId));
        requireConfigurable(tenant);

        long version = contacts.nextVersion(tenantId, command.contactId());
        OperationalContact contact = new OperationalContact(
                tenantId,
                command.contactId(),
                version,
                command.displayName(),
                command.email(),
                command.purpose(),
                command.active(),
                clock.instant(),
                actorIdentity(command.actor())
        );
        contacts.append(contact);
        audit.appendOperationalContactChanged(
                command.actor().issuer(),
                command.actor().subject(),
                tenantId.value(),
                command.contactId(),
                version,
                command.reason(),
                command.context().correlationId()
        );
        return contact;
    }

    @Transactional(readOnly = true)
    public java.util.List<OperationalContact> current(
            TenantReference tenant,
            AuthorizedRequestContext context
    ) {
        TenantId tenantId = authorizeRead(tenant, context);
        return contacts.currentAll(tenantId);
    }

    @Transactional(readOnly = true)
    public OperationalContact current(
            TenantReference tenant,
            java.util.UUID contactId,
            AuthorizedRequestContext context
    ) {
        TenantId tenantId = authorizeRead(tenant, context);
        return contacts.current(tenantId, contactId)
                .orElseThrow(() -> new OperationalContactNotFoundException(tenantId, contactId));
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

    private TenantId authorizeRead(TenantReference tenant, AuthorizedRequestContext context) {
        if (!context.tenantId().equals(tenant.value())) {
            throw new AuthorizationResourceNotFoundException();
        }
        if (!context.isHuman() || !context.canReadTenant()) {
            throw new AuthorizationPermissionDeniedException("tenant:read");
        }
        return TenantId.from(tenant.value());
    }

    private void requireConfigurable(Tenant tenant) {
        if (tenant.status() != TenantStatus.PENDING_ACTIVATION
                && tenant.status() != TenantStatus.ACTIVE) {
            throw new IllegalStateException(
                    "Tenant operational contacts cannot change while Tenant is "
                            + tenant.status());
        }
    }

    private String actorIdentity(AuthenticatedPrincipal actor) {
        return actor.issuer() + "|" + actor.subject();
    }
}
