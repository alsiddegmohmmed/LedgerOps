package com.ledgerops.customer.domain;

import com.ledgerops.merchant.api.MerchantReference;

import java.util.Objects;

public final class Customer {

    private final CustomerId id;
    private final MerchantReference merchantReference;
    private final CustomerReference customerReference;
    private final CustomerStatus status;

    public Customer(
            CustomerId id,
            MerchantReference merchantReference,
            CustomerReference customerReference,
            CustomerStatus status
    ) {
        this.id = Objects.requireNonNull(id, "Customer ID must not be null");
        this.merchantReference = Objects.requireNonNull(
                merchantReference,
                "Merchant reference must not be null"
        );
        this.customerReference = Objects.requireNonNull(
                customerReference,
                "Customer reference must not be null"
        );
        this.status = Objects.requireNonNull(status, "Customer status must not be null");
    }

    public CustomerId id() {
        return id;
    }

    public MerchantReference merchantReference() {
        return merchantReference;
    }

    public CustomerReference customerReference() {
        return customerReference;
    }

    public CustomerStatus status() {
        return status;
    }
}
