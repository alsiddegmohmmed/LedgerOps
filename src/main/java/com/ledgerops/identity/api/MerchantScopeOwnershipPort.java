package com.ledgerops.identity.api;

import java.util.Set;
import java.util.UUID;

/**
 * Narrow ownership fact required by Identity when creating Merchant-scoped
 * role assignments. Merchant owns the implementation and its data.
 */
public interface MerchantScopeOwnershipPort {

    boolean allBelongToTenant(UUID tenantId, Set<UUID> merchantIds);
}
