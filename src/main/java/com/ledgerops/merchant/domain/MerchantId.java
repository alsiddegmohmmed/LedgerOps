package com.ledgerops.merchant.domain;

import java.util.Objects;
import java.util.UUID;

public record MerchantId(UUID value) {

    public MerchantId {
        Objects.requireNonNull(value, "Merchant ID must not be null");
    }

    public static MerchantId newId() {
        return new MerchantId(UUID.randomUUID());
    }

    public static MerchantId from(UUID value) {
        return new MerchantId(value);
    }
}
