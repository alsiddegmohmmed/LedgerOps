package com.ledgerops.identity.domain;

import java.util.UUID;

public interface TenantActivationReadRepository {

    TenantActivationFacts assess(UUID tenantId);
}
