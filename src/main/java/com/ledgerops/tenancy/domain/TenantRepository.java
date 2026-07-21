package com.ledgerops.tenancy.domain;

import java.util.Optional;

public interface TenantRepository {

    Tenant save(Tenant tenant);

    Optional<Tenant> findById(TenantId tenantId);

    Optional<Tenant> findByIdForUpdate(TenantId tenantId);

    boolean existsByName(String name);
}
