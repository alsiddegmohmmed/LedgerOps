package com.ledgerops.merchant.api;

public interface MerchantLifecyclePort {

    MerchantReference suspend(MerchantLifecycleRequest request);

    MerchantReference activate(MerchantLifecycleRequest request);
}
