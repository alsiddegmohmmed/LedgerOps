package com.ledgerops.customer.infrastructure;

import com.ledgerops.customer.domain.Customer;
import com.ledgerops.customer.domain.CustomerId;
import com.ledgerops.customer.domain.CustomerReference;
import com.ledgerops.customer.domain.CustomerRepository;
import com.ledgerops.customer.domain.CustomerStatus;
import com.ledgerops.merchant.api.MerchantReference;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

@Repository
class CustomerPersistenceAdapter implements CustomerRepository {

    private final SpringDataCustomerRepository repository;
    private final Clock clock;

    CustomerPersistenceAdapter(
            SpringDataCustomerRepository repository,
            Clock clock
    ) {
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    @Transactional
    public Customer save(Customer customer) {
        Instant now = clock.instant();
        MerchantReference merchant = customer.merchantReference();
        CustomerJpaEntity entity = repository.findByTenantIdAndMerchantIdAndId(
                        merchant.tenantId(),
                        merchant.value(),
                        customer.id().value()
                )
                .map(existing -> update(existing, customer, now))
                .orElseGet(() -> create(customer, now));

        return toDomain(repository.saveAndFlush(entity));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Customer> findById(
            MerchantReference merchantReference,
            CustomerId customerId
    ) {
        return repository.findByTenantIdAndMerchantIdAndId(
                        merchantReference.tenantId(),
                        merchantReference.value(),
                        customerId.value()
                )
                .map(this::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Customer> findByReference(
            MerchantReference merchantReference,
            CustomerReference customerReference
    ) {
        return repository.findByTenantIdAndMerchantIdAndCustomerReference(
                        merchantReference.tenantId(),
                        merchantReference.value(),
                        customerReference.value()
                )
                .map(this::toDomain);
    }

    private CustomerJpaEntity create(Customer customer, Instant now) {
        MerchantReference merchant = customer.merchantReference();
        return new CustomerJpaEntity(
                customer.id().value(),
                merchant.tenantId(),
                merchant.value(),
                customer.customerReference().value(),
                customer.status().name(),
                now,
                now
        );
    }

    private CustomerJpaEntity update(
            CustomerJpaEntity entity,
            Customer customer,
            Instant now
    ) {
        entity.update(
                customer.customerReference().value(),
                customer.status().name(),
                now
        );
        return entity;
    }

    private Customer toDomain(CustomerJpaEntity entity) {
        MerchantReference merchantReference = MerchantReference.from(
                entity.tenantId(),
                entity.merchantId()
        );
        return new Customer(
                CustomerId.from(entity.id()),
                merchantReference,
                CustomerReference.from(entity.customerReference()),
                CustomerStatus.valueOf(entity.status())
        );
    }
}
