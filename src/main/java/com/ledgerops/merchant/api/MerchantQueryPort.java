package com.ledgerops.merchant.api;

import com.ledgerops.tenancy.api.TenantReference;

import java.util.List;
import java.util.UUID;

public interface MerchantQueryPort {

    List<MerchantResponse> current(
            TenantReference tenant,
            MerchantReadAuthorization authorization
    );

    MerchantResponse current(
            TenantReference tenant,
            UUID merchantId,
            MerchantReadAuthorization authorization
    );
}
