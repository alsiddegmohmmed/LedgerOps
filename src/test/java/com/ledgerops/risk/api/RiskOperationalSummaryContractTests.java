package com.ledgerops.risk.api;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RiskOperationalSummaryContractTests {

    @Test
    void exposesCreationTimeAndMerchantWithoutExposingRiskInternals() {
        UUID reviewId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-08-13T00:00:00Z");

        RiskReviewOperationalSummary value = new RiskReviewOperationalSummary(
                reviewId, tenantId, paymentId, merchantId, createdAt);

        assertEquals(reviewId, value.reviewId());
        assertEquals(createdAt, value.createdAt());
    }

    @Test
    void rejectsMissingMerchantBecauseRiskReviewsAreMerchantScoped() {
        assertThrows(NullPointerException.class, () -> new RiskReviewOperationalSummary(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null,
                Instant.parse("2026-08-13T00:00:00Z")));
    }
}
