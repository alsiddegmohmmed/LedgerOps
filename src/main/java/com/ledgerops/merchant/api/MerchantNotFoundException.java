package com.ledgerops.merchant.api;

public final class MerchantNotFoundException extends RuntimeException {

    public MerchantNotFoundException() {
        super("Merchant is not available");
    }
}
