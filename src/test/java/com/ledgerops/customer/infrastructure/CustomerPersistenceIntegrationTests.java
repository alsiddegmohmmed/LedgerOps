package com.ledgerops.customer.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ledgerops.customer.domain.Customer;
import com.ledgerops.customer.domain.CustomerId;
import com.ledgerops.customer.domain.CustomerReference;
import com.ledgerops.customer.domain.CustomerRepository;
import com.ledgerops.customer.domain.CustomerStatus;
import com.ledgerops.merchant.api.MerchantReference;
import com.ledgerops.support.PostgresTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.UUID;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
class CustomerPersistenceIntegrationTests {

    @Autowired
    private CustomerRepository customerRepository;

    @Test
    void savesAndLoadsCustomerInsideMerchantContext() {
        MerchantReference merchantReference = merchantReference();
        Customer customer = customer(
                CustomerId.newId(),
                merchantReference,
                "customer-persistence-1001"
        );

        customerRepository.save(customer);

        Customer loaded = customerRepository.findById(
                        merchantReference,
                        customer.id()
                )
                .orElseThrow();

        assertEquals(customer.id(), loaded.id());
        assertEquals(merchantReference, loaded.merchantReference());
        assertEquals("customer-persistence-1001", loaded.customerReference().value());
        assertEquals(CustomerStatus.ACTIVE, loaded.status());
    }

    @Test
    void scopedLookupDoesNotDiscloseCustomerAcrossMerchantOrTenant() {
        UUID tenantId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();
        MerchantReference owner = MerchantReference.from(tenantId, merchantId);
        MerchantReference otherMerchant = MerchantReference.from(
                tenantId,
                UUID.randomUUID()
        );
        MerchantReference otherTenant = MerchantReference.from(
                UUID.randomUUID(),
                merchantId
        );
        Customer customer = customer(
                CustomerId.newId(),
                owner,
                "customer-isolation-1001"
        );

        customerRepository.save(customer);

        assertTrue(customerRepository.findById(owner, customer.id()).isPresent());
        assertFalse(customerRepository.findById(
                otherMerchant,
                customer.id()
        ).isPresent());
        assertFalse(customerRepository.findById(
                otherTenant,
                customer.id()
        ).isPresent());
    }

    @Test
    void customerReferenceUniquenessIsScopedToMerchantContext() {
        UUID tenantId = UUID.randomUUID();
        MerchantReference firstMerchant = MerchantReference.from(
                tenantId,
                UUID.randomUUID()
        );
        MerchantReference secondMerchant = MerchantReference.from(
                tenantId,
                UUID.randomUUID()
        );

        customerRepository.save(customer(
                CustomerId.newId(),
                firstMerchant,
                "shared-customer-reference"
        ));
        customerRepository.save(customer(
                CustomerId.newId(),
                secondMerchant,
                "shared-customer-reference"
        ));

        assertTrue(customerRepository.findByReference(
                firstMerchant,
                CustomerReference.from("shared-customer-reference")
        ).isPresent());
        assertTrue(customerRepository.findByReference(
                secondMerchant,
                CustomerReference.from("shared-customer-reference")
        ).isPresent());
    }

    @Test
    void duplicateCustomerReferenceInsideMerchantIsRejectedByPostgres() {
        MerchantReference merchantReference = merchantReference();
        customerRepository.save(customer(
                CustomerId.newId(),
                merchantReference,
                "duplicate-customer-reference"
        ));

        Customer duplicate = customer(
                CustomerId.newId(),
                merchantReference,
                "duplicate-customer-reference"
        );

        assertThrows(
                DataIntegrityViolationException.class,
                () -> customerRepository.save(duplicate)
        );
    }

    @Test
    void customerIdentityCannotBeReassignedToAnotherMerchant() {
        CustomerId customerId = CustomerId.newId();
        customerRepository.save(customer(
                customerId,
                merchantReference(),
                "original-merchant-customer"
        ));

        Customer reassigned = customer(
                customerId,
                merchantReference(),
                "other-merchant-customer"
        );

        assertThrows(
                DataIntegrityViolationException.class,
                () -> customerRepository.save(reassigned)
        );
    }

    private Customer customer(
            CustomerId id,
            MerchantReference merchantReference,
            String reference
    ) {
        return new Customer(
                id,
                merchantReference,
                CustomerReference.from(reference),
                CustomerStatus.ACTIVE
        );
    }

    private MerchantReference merchantReference() {
        return MerchantReference.from(UUID.randomUUID(), UUID.randomUUID());
    }
}
