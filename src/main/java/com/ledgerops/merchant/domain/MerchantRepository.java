package com.ledgerops.merchant.domain;

import com.ledgerops.tenancy.api.TenantReference;

import java.util.List;
import java.util.Optional;

public interface MerchantRepository {

    Merchant save(Merchant merchant);

    Optional<Merchant> findById(
            TenantReference tenantReference,
            MerchantId merchantId
    );

    Optional<Merchant> findByIdForUpdate(
            TenantReference tenantReference,
            MerchantId merchantId
    );

    List<Merchant> findAll(TenantReference tenantReference);

    boolean existsActiveByTenant(TenantReference tenantReference);

    boolean existsByName(TenantReference tenantReference, String name);
}
