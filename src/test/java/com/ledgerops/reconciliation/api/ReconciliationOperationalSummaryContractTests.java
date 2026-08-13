package com.ledgerops.reconciliation.api;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

class ReconciliationOperationalSummaryContractTests {

    @Test
    void permitsTenantWideDiscrepanciesWithoutAMerchantAssociation() {
        ReconciliationDiscrepancyOperationalSummary value =
                new ReconciliationDiscrepancyOperationalSummary(
                        UUID.randomUUID(), UUID.randomUUID(), null, null, null,
                        Instant.parse("2026-08-13T00:00:00Z"), false);

        assertNull(value.merchantId());
        assertFalse(value.currentReconciliationRun());
    }
}
