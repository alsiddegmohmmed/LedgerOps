package com.ledgerops.merchant.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ledgerops.tenancy.api.TenantReference;
import org.junit.jupiter.api.Test;

import java.util.UUID;

class MerchantTests {

    @Test
    void createsTenantOwnedMerchantWithRequiredAttributes() {
        MerchantId merchantId = MerchantId.from(
                UUID.fromString("6e5b5e31-9bc7-497c-9a1f-b006910ba426")
        );
        TenantReference tenantReference = TenantReference.from(
                UUID.fromString("cd44c30b-9d1b-4a65-9de4-172bc5e10663")
        );

        Merchant merchant = new Merchant(
                merchantId,
                tenantReference,
                "  Acme Merchant  ",
                MerchantStatus.ACTIVE
        );

        assertEquals(merchantId, merchant.id());
        assertEquals(tenantReference, merchant.tenantReference());
        assertEquals("Acme Merchant", merchant.name());
        assertEquals(MerchantStatus.ACTIVE, merchant.status());
    }

    @Test
    void rejectsMissingTenantOwnership() {
        NullPointerException exception = assertThrows(
                NullPointerException.class,
                () -> new Merchant(
                        MerchantId.newId(),
                        null,
                        "Acme Merchant",
                        MerchantStatus.ACTIVE
                )
        );

        assertEquals("Tenant reference must not be null", exception.getMessage());
    }

    @Test
    void rejectsBlankMerchantName() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> merchantNamed("   ")
        );

        assertEquals("Merchant name must not be blank", exception.getMessage());
    }

    @Test
    void rejectsMerchantNameLongerThanDatabaseBoundary() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> merchantNamed("a".repeat(121))
        );

        assertEquals(
                "Merchant name must not exceed 120 characters",
                exception.getMessage()
        );
    }

    private Merchant merchantNamed(String name) {
        return new Merchant(
                MerchantId.newId(),
                TenantReference.from(UUID.randomUUID()),
                name,
                MerchantStatus.ACTIVE
        );
    }
}
