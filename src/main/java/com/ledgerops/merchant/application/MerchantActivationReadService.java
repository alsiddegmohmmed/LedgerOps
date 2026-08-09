package com.ledgerops.merchant.application;

import com.ledgerops.merchant.api.MerchantActivationReadPort;
import com.ledgerops.merchant.domain.MerchantRepository;
import com.ledgerops.tenancy.api.TenantReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class MerchantActivationReadService implements MerchantActivationReadPort {

    private final MerchantRepository merchants;

    MerchantActivationReadService(MerchantRepository merchants) {
        this.merchants = merchants;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasActiveMerchant(TenantReference tenant) {
        return merchants.existsActiveByTenant(tenant);
    }
}
