package com.ledgerops.customer.application;

import com.ledgerops.customer.api.CustomerActivityQuery;
import com.ledgerops.customer.api.CustomerActivityStatus;
import com.ledgerops.customer.domain.CustomerId;
import com.ledgerops.customer.domain.CustomerRepository;
import com.ledgerops.customer.domain.CustomerStatus;
import com.ledgerops.merchant.api.MerchantReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
class CustomerActivityQueryService implements CustomerActivityQuery {

    private final CustomerRepository customerRepository;

    CustomerActivityQueryService(CustomerRepository customerRepository) {
        this.customerRepository = customerRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public CustomerActivityStatus evaluate(
            MerchantReference merchantReference,
            UUID customerId
    ) {
        return customerRepository.findById(
                        merchantReference,
                        CustomerId.from(customerId)
                )
                .map(customer -> customer.status() == CustomerStatus.ACTIVE
                        ? CustomerActivityStatus.ALLOWED
                        : CustomerActivityStatus.INACTIVE)
                .orElse(CustomerActivityStatus.NOT_FOUND);
    }
}
