package com.ledgerops.merchant.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.util.UUID;

class MerchantReferenceTests {

    @Test
    void carriesOpaqueTenantAndMerchantIdentity() {
        UUID tenantId = UUID.fromString("75f8963f-512b-4907-b773-cf0bc890bbaf");
        UUID merchantId = UUID.fromString("8e0f11e5-68a6-4f34-b971-ae136e304db3");

        MerchantReference reference = MerchantReference.from(tenantId, merchantId);

        assertEquals(tenantId, reference.tenantId());
        assertEquals(merchantId, reference.value());
    }

    @Test
    void rejectsMissingOwnershipContext() {
        UUID merchantId = UUID.randomUUID();

        assertThrows(
                NullPointerException.class,
                () -> MerchantReference.from(null, merchantId)
        );
        assertThrows(
                NullPointerException.class,
                () -> MerchantReference.from(UUID.randomUUID(), null)
        );
    }
}
