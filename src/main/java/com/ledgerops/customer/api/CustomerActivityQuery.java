package com.ledgerops.customer.api;

import com.ledgerops.merchant.api.MerchantReference;

import java.util.UUID;

public interface CustomerActivityQuery {

    CustomerActivityStatus evaluate(
            MerchantReference merchantReference,
            UUID customerId
    );
}
