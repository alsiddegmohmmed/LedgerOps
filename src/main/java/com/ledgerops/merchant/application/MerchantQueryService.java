package com.ledgerops.merchant.application;

import com.ledgerops.merchant.api.MerchantQueryPort;
import com.ledgerops.merchant.api.MerchantNotFoundException;
import com.ledgerops.merchant.api.MerchantReadAuthorization;
import com.ledgerops.merchant.api.MerchantResponse;
import com.ledgerops.merchant.domain.Merchant;
import com.ledgerops.merchant.domain.MerchantId;
import com.ledgerops.merchant.domain.MerchantRepository;
import com.ledgerops.tenancy.api.TenantReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
class MerchantQueryService implements MerchantQueryPort {

    private final MerchantRepository merchants;

    public MerchantQueryService(MerchantRepository merchants) {
        this.merchants = merchants;
    }

    @Override
    @Transactional(readOnly = true)
    public List<MerchantResponse> current(
            TenantReference tenant,
            MerchantReadAuthorization authorization
    ) {
        authorize(tenant, authorization);
        return merchants.findAll(tenant).stream()
                .filter(merchant -> authorization.allows(merchant.id().value()))
                .map(MerchantResponse::from)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public MerchantResponse current(
            TenantReference tenant,
            UUID merchantId,
            MerchantReadAuthorization authorization
    ) {
        authorize(tenant, authorization);
        Merchant merchant = merchants.findById(tenant, MerchantId.from(merchantId))
                .orElseThrow(MerchantNotFoundException::new);
        if (!authorization.allows(merchant.id().value())) {
            throw new MerchantNotFoundException();
        }
        return MerchantResponse.from(merchant);
    }

    private void authorize(
            TenantReference tenant,
            MerchantReadAuthorization authorization
    ) {
        if (!authorization.tenantId().equals(tenant.value())) {
            throw new MerchantNotFoundException();
        }
    }
}
