package com.ledgerops.administration.merchant.api;

import com.ledgerops.administration.api.MerchantLifecycleResult;

import java.util.UUID;

record MerchantLifecycleHttpResponse(
        UUID tenantId,
        UUID merchantId,
        String status
) {

    static MerchantLifecycleHttpResponse from(MerchantLifecycleResult result) {
        return new MerchantLifecycleHttpResponse(
                result.tenantId(), result.merchantId(), result.status());
    }
}
