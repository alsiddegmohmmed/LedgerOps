package com.ledgerops.merchant.domain;

import com.ledgerops.tenancy.api.TenantReference;

import java.util.Optional;

public interface MerchantRepository {

    Merchant save(Merchant merchant);

    Optional<Merchant> findById(
            TenantReference tenantReference,
            MerchantId merchantId
    );

    boolean existsByName(TenantReference tenantReference, String name);
}
