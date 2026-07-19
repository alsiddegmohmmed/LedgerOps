package com.ledgerops.customer.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ledgerops.merchant.api.MerchantReference;
import org.junit.jupiter.api.Test;

import java.util.UUID;

class CustomerTests {

    @Test
    void createsCustomerWithMerchantScopedReferenceAndStatus() {
        CustomerId customerId = CustomerId.from(
                UUID.fromString("80abb5f8-b19a-447a-9cdd-0959196d5bd3")
        );
        MerchantReference merchantReference = MerchantReference.from(
                UUID.fromString("c2bd2342-7fb4-483c-a035-25b0588f748a"),
                UUID.fromString("8676da11-7b2c-43d3-aaeb-a783716da567")
        );

        Customer customer = new Customer(
                customerId,
                merchantReference,
                CustomerReference.from("  customer-1001  "),
                CustomerStatus.ACTIVE
        );

        assertEquals(customerId, customer.id());
        assertEquals(merchantReference, customer.merchantReference());
        assertEquals("customer-1001", customer.customerReference().value());
        assertEquals(CustomerStatus.ACTIVE, customer.status());
    }

    @Test
    void rejectsMissingMerchantRelationship() {
        NullPointerException exception = assertThrows(
                NullPointerException.class,
                () -> new Customer(
                        CustomerId.newId(),
                        null,
                        CustomerReference.from("customer-1001"),
                        CustomerStatus.ACTIVE
                )
        );

        assertEquals("Merchant reference must not be null", exception.getMessage());
    }

    @Test
    void rejectsBlankCustomerReference() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> CustomerReference.from("   ")
        );

        assertEquals("Customer reference must not be blank", exception.getMessage());
    }

    @Test
    void rejectsCustomerReferenceLongerThanDatabaseBoundary() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> CustomerReference.from("a".repeat(121))
        );

        assertEquals(
                "Customer reference must not exceed 120 characters",
                exception.getMessage()
        );
    }
}
