package com.ledgerops.merchant.api;

public interface MerchantActivityQuery {

    MerchantActivityStatus evaluate(MerchantReference merchantReference);
}
