package com.ledgerops.tenancy.infrastructure;

import com.ledgerops.tenancy.domain.Tenant;
import com.ledgerops.tenancy.domain.TenantId;
import com.ledgerops.tenancy.domain.TenantRepository;
import com.ledgerops.tenancy.domain.TenantStatus;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Currency;
import java.util.Locale;
import java.util.Optional;

@Repository
class TenantPersistenceAdapter implements TenantRepository {

    private final SpringDataTenantRepository repository;
    private final Clock clock;

    TenantPersistenceAdapter(
            SpringDataTenantRepository repository,
            Clock clock
    ) {
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    @Transactional
    public Tenant save(Tenant tenant) {
        Instant now = clock.instant();

        TenantJpaEntity entity = repository.findById(tenant.id().value())
                .map(existing -> update(existing, tenant, now))
                .orElseGet(() -> create(tenant, now));

        return toDomain(repository.save(entity));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Tenant> findById(TenantId tenantId) {
        return repository.findById(tenantId.value())
                .map(this::toDomain);
    }

    private TenantJpaEntity create(Tenant tenant, Instant now) {
        return new TenantJpaEntity(
                tenant.id().value(),
                tenant.name(),
                tenant.defaultCurrency().getCurrencyCode(),
                tenant.defaultLocale().toLanguageTag(),
                tenant.status().name(),
                now,
                now
        );
    }

    private TenantJpaEntity update(
            TenantJpaEntity entity,
            Tenant tenant,
            Instant now
    ) {
        entity.update(
                tenant.name(),
                tenant.defaultCurrency().getCurrencyCode(),
                tenant.defaultLocale().toLanguageTag(),
                tenant.status().name(),
                now
        );

        return entity;
    }

    private Tenant toDomain(TenantJpaEntity entity) {
        return new Tenant(
                TenantId.from(entity.id()),
                entity.name(),
                Currency.getInstance(entity.defaultCurrency()),
                Locale.forLanguageTag(entity.defaultLocale()),
                TenantStatus.valueOf(entity.status())
        );
    }
}
