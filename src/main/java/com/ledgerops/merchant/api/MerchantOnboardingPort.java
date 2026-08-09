package com.ledgerops.merchant.api;

public interface MerchantOnboardingPort {

    MerchantReference createInitialActiveMerchant(MerchantOnboardingRequest request);
}
