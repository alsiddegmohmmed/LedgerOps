package com.ledgerops.tenancy.domain;

import java.util.Optional;

public interface TenantConfigurationRepository {

    long nextVersion(TenantId tenantId);

    void append(TenantConfiguration configuration);

    Optional<TenantConfiguration> current(TenantId tenantId);
}
