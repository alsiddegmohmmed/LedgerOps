package com.ledgerops.customer.domain;

import com.ledgerops.merchant.api.MerchantReference;

import java.util.Optional;

public interface CustomerRepository {

    Customer save(Customer customer);

    Optional<Customer> findById(
            MerchantReference merchantReference,
            CustomerId customerId
    );

    Optional<Customer> findByReference(
            MerchantReference merchantReference,
            CustomerReference customerReference
    );
}
