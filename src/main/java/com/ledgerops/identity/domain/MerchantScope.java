package com.ledgerops.identity.domain;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class MerchantScope {
    private final UUID tenantId;
    private final Set<UUID> merchantIds;

    private MerchantScope(UUID tenantId, Set<UUID> merchantIds) {
        this.tenantId = tenantId;
        this.merchantIds = merchantIds;
    }

    public static MerchantScope validated(
            UUID tenantId,
            Set<UUID> merchantIds,
            Map<UUID, UUID> merchantTenantIds
    ) {
        Objects.requireNonNull(tenantId, "Scope Tenant ID must not be null");
        Objects.requireNonNull(merchantIds, "Merchant IDs must not be null");
        Objects.requireNonNull(merchantTenantIds, "Merchant ownership facts must not be null");
        if (merchantIds.isEmpty()) {
            throw new InvalidRoleAssignmentException("Merchant scope must not be empty");
        }
        if (merchantIds.stream().anyMatch(Objects::isNull)) {
            throw new InvalidRoleAssignmentException("Merchant scope cannot contain a null Merchant ID");
        }
        Set<UUID> immutableMerchantIds = Set.copyOf(merchantIds);
        for (UUID merchantId : immutableMerchantIds) {
            UUID ownerTenantId = merchantTenantIds.get(merchantId);
            if (ownerTenantId == null) {
                throw new InvalidRoleAssignmentException(
                        "Merchant ownership fact is missing for " + merchantId
                );
            }
            if (!tenantId.equals(ownerTenantId)) {
                throw new InvalidRoleAssignmentException(
                        "Merchant " + merchantId + " does not belong to Tenant " + tenantId
                );
            }
        }
        return new MerchantScope(tenantId, immutableMerchantIds);
    }

    public UUID tenantId() {
        return tenantId;
    }

    public Set<UUID> merchantIds() {
        return merchantIds;
    }
}
