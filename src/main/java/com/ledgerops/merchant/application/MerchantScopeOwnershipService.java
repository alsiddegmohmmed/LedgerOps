package com.ledgerops.merchant.application;

import com.ledgerops.identity.api.MerchantScopeOwnershipPort;
import com.ledgerops.merchant.domain.MerchantId;
import com.ledgerops.merchant.domain.MerchantRepository;
import com.ledgerops.tenancy.api.TenantReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.UUID;

/** Merchant-owned implementation of the Identity ownership fact port. */
@Service
class MerchantScopeOwnershipService implements MerchantScopeOwnershipPort {

    private final MerchantRepository merchants;

    MerchantScopeOwnershipService(MerchantRepository merchants) {
        this.merchants = merchants;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean allBelongToTenant(UUID tenantId, Set<UUID> merchantIds) {
        if (tenantId == null || merchantIds == null || merchantIds.isEmpty()
                || merchantIds.stream().anyMatch(java.util.Objects::isNull)) {
            return false;
        }
        TenantReference tenant = TenantReference.from(tenantId);
        return merchantIds.stream().allMatch(merchantId ->
                merchants.findById(tenant, MerchantId.from(merchantId)).isPresent());
    }
}
