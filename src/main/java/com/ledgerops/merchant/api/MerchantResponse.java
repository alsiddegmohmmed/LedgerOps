package com.ledgerops.merchant.api;

import com.ledgerops.merchant.domain.Merchant;

import java.util.UUID;

public record MerchantResponse(
        UUID tenantId,
        UUID merchantId,
        String name,
        String status,
        long version
) {

    public static MerchantResponse from(Merchant merchant) {
        return new MerchantResponse(
                merchant.tenantReference().value(),
                merchant.id().value(),
                merchant.name(),
                merchant.status().name(),
                merchant.version()
        );
    }
}
