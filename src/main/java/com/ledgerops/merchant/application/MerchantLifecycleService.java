package com.ledgerops.merchant.application;

import com.ledgerops.audit.api.AuditAppendPort;
import com.ledgerops.merchant.api.MerchantLifecyclePort;
import com.ledgerops.merchant.api.MerchantLifecycleRequest;
import com.ledgerops.merchant.api.MerchantReference;
import com.ledgerops.merchant.domain.Merchant;
import com.ledgerops.merchant.domain.MerchantId;
import com.ledgerops.merchant.domain.MerchantRepository;
import com.ledgerops.merchant.domain.MerchantStatus;
import com.ledgerops.messaging.api.MessageOutbox;
import com.ledgerops.tenancy.api.TenantReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.function.UnaryOperator;

@Service
class MerchantLifecycleService implements MerchantLifecyclePort {

    private final MerchantRepository merchants;
    private final AuditAppendPort audit;
    private final MessageOutbox outbox;
    private final Clock clock;

    MerchantLifecycleService(
            MerchantRepository merchants,
            AuditAppendPort audit,
            MessageOutbox outbox,
            Clock clock
    ) {
        this.merchants = merchants;
        this.audit = audit;
        this.outbox = outbox;
        this.clock = clock;
    }

    @Override
    @Transactional
    public MerchantReference suspend(MerchantLifecycleRequest request) {
        return transition(request, Merchant::suspend, MerchantStatus.SUSPENDED);
    }

    @Override
    @Transactional
    public MerchantReference activate(MerchantLifecycleRequest request) {
        return transition(request, Merchant::activate, MerchantStatus.ACTIVE);
    }

    private MerchantReference transition(
            MerchantLifecycleRequest request,
            UnaryOperator<Merchant> transition,
            MerchantStatus targetStatus
    ) {
        TenantReference tenant = TenantReference.from(request.merchant().tenantId());
        MerchantId merchantId = MerchantId.from(request.merchant().value());
        Merchant current = merchants.findByIdForUpdate(tenant, merchantId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Merchant not found: " + request.merchant()));
        MerchantStatus previousStatus = current.status();
        Merchant changed;
        try {
            changed = transition.apply(current);
        } catch (IllegalStateException exception) {
            throw new IllegalStateException(
                    "Merchant cannot transition from " + previousStatus
                            + " to " + targetStatus,
                    exception
            );
        }

        Merchant saved = merchants.save(changed);
        Instant occurredAt = clock.instant();
        outbox.appendOrGet(MerchantLifecycleOutboxFactory.changed(
                tenant.value(),
                saved.id().value(),
                previousStatus.name(),
                saved.status().name(),
                saved.version(),
                request.correlationId(),
                request.operationId(),
                occurredAt
        ));
        audit.appendMerchantLifecycleChanged(
                request.actorIssuer(),
                request.actorSubject(),
                tenant.value(),
                saved.id().value(),
                previousStatus.name(),
                saved.status().name(),
                request.correlationId().toString()
        );
        return MerchantReference.from(tenant.value(), saved.id().value());
    }
}
