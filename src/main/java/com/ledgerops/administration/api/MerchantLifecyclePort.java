package com.ledgerops.administration.api;

public interface MerchantLifecyclePort {

    MerchantLifecycleResult suspend(MerchantLifecycleCommand command);

    MerchantLifecycleResult activate(MerchantLifecycleCommand command);
}
