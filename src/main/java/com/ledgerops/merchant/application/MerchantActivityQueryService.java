package com.ledgerops.merchant.application;

import com.ledgerops.merchant.api.MerchantActivityQuery;
import com.ledgerops.merchant.api.MerchantActivityStatus;
import com.ledgerops.merchant.api.MerchantReference;
import com.ledgerops.merchant.domain.MerchantId;
import com.ledgerops.merchant.domain.MerchantRepository;
import com.ledgerops.merchant.domain.MerchantStatus;
import com.ledgerops.tenancy.api.TenantReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class MerchantActivityQueryService implements MerchantActivityQuery {

    private final MerchantRepository merchantRepository;

    MerchantActivityQueryService(MerchantRepository merchantRepository) {
        this.merchantRepository = merchantRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public MerchantActivityStatus evaluate(MerchantReference merchantReference) {
        return merchantRepository.findById(
                        TenantReference.from(merchantReference.tenantId()),
                        MerchantId.from(merchantReference.value())
                )
                .map(merchant -> merchant.status() == MerchantStatus.ACTIVE
                        ? MerchantActivityStatus.ALLOWED
                        : MerchantActivityStatus.INACTIVE)
                .orElse(MerchantActivityStatus.NOT_FOUND);
    }
}
