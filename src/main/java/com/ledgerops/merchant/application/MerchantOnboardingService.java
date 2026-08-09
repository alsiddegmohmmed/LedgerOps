package com.ledgerops.merchant.application;

import com.ledgerops.merchant.api.MerchantOnboardingPort;
import com.ledgerops.merchant.api.MerchantOnboardingRequest;
import com.ledgerops.merchant.api.MerchantReference;
import com.ledgerops.merchant.domain.Merchant;
import com.ledgerops.merchant.domain.MerchantId;
import com.ledgerops.merchant.domain.MerchantRepository;
import com.ledgerops.merchant.domain.MerchantStatus;
import com.ledgerops.messaging.api.MessageOutbox;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;

@Service
class MerchantOnboardingService implements MerchantOnboardingPort {

    private final MerchantRepository merchants;
    private final MessageOutbox outbox;
    private final Clock clock;

    MerchantOnboardingService(
            MerchantRepository merchants,
            MessageOutbox outbox,
            Clock clock
    ) {
        this.merchants = merchants;
        this.outbox = outbox;
        this.clock = clock;
    }

    @Override
    @Transactional
    public MerchantReference createInitialActiveMerchant(MerchantOnboardingRequest request) {
        String normalizedName = request.name().trim();
        if (merchants.existsByName(request.tenant(), normalizedName)) {
            throw new IllegalStateException(
                    "Merchant name already exists for Tenant: " + normalizedName);
        }

        Merchant merchant = new Merchant(
                MerchantId.newId(),
                request.tenant(),
                request.name(),
                MerchantStatus.ACTIVE
        );
        Merchant saved = merchants.save(merchant);
        Instant occurredAt = clock.instant();
        outbox.appendOrGet(MerchantLifecycleOutboxFactory.created(
                saved.tenantReference().value(),
                saved.id().value(),
                request.correlationId(),
                request.operationId(),
                occurredAt
        ));
        return MerchantReference.from(
                saved.tenantReference().value(), saved.id().value());
    }
}
