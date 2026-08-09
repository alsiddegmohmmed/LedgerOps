package com.ledgerops.identity.api;

import java.util.UUID;

public interface TenantActivationReadPort {

    TenantActivationReadiness assess(UUID tenantId);
}
