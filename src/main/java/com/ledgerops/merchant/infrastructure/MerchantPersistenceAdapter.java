package com.ledgerops.merchant.infrastructure;

import com.ledgerops.merchant.domain.Merchant;
import com.ledgerops.merchant.domain.MerchantId;
import com.ledgerops.merchant.domain.MerchantRepository;
import com.ledgerops.merchant.domain.MerchantStatus;
import com.ledgerops.tenancy.api.TenantReference;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

@Repository
class MerchantPersistenceAdapter implements MerchantRepository {

    private final SpringDataMerchantRepository repository;
    private final Clock clock;

    MerchantPersistenceAdapter(
            SpringDataMerchantRepository repository,
            Clock clock
    ) {
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    @Transactional
    public Merchant save(Merchant merchant) {
        Instant now = clock.instant();
        MerchantJpaEntity entity = repository.findByTenantIdAndId(
                        merchant.tenantReference().value(),
                        merchant.id().value()
                )
                .map(existing -> update(existing, merchant, now))
                .orElseGet(() -> create(merchant, now));

        return toDomain(repository.saveAndFlush(entity));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Merchant> findById(
            TenantReference tenantReference,
            MerchantId merchantId
    ) {
        return repository.findByTenantIdAndId(
                        tenantReference.value(),
                        merchantId.value()
                )
                .map(this::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean existsByName(
            TenantReference tenantReference,
            String name
    ) {
        return repository.existsByTenantIdAndName(
                tenantReference.value(),
                name
        );
    }

    private MerchantJpaEntity create(Merchant merchant, Instant now) {
        return new MerchantJpaEntity(
                merchant.id().value(),
                merchant.tenantReference().value(),
                merchant.name(),
                merchant.status().name(),
                now,
                now
        );
    }

    private MerchantJpaEntity update(
            MerchantJpaEntity entity,
            Merchant merchant,
            Instant now
    ) {
        entity.update(merchant.name(), merchant.status().name(), now);
        return entity;
    }

    private Merchant toDomain(MerchantJpaEntity entity) {
        return new Merchant(
                MerchantId.from(entity.id()),
                TenantReference.from(entity.tenantId()),
                entity.name(),
                MerchantStatus.valueOf(entity.status())
        );
    }
}
