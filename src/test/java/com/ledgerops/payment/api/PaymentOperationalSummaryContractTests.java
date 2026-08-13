package com.ledgerops.payment.api;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PaymentOperationalSummaryContractTests {

    @Test
    void acceptsOnlyDurableFinalProviderOutcomeCategories() {
        PaymentOperationalSummaryOutcome outcome = new PaymentOperationalSummaryOutcome(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "SUCCESS",
                Instant.parse("2026-08-13T00:00:00Z"));

        assertEquals("SUCCESS", outcome.finalCategory());
        assertEquals(true, outcome.successful());
        assertEquals(false, outcome.failed());
    }

    @Test
    void rejectsNonFinalProviderOutcomes() {
        assertThrows(IllegalArgumentException.class, () -> new PaymentOperationalSummaryOutcome(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "PENDING",
                Instant.parse("2026-08-13T00:00:00Z")));
    }

    @Test
    void requiresPositivePaymentAmountAndUppercaseCurrency() {
        assertThrows(IllegalArgumentException.class, () -> new PaymentOperationalSummaryPayment(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                BigDecimal.ZERO, "SAR", Instant.parse("2026-08-13T00:00:00Z")));
        assertThrows(IllegalArgumentException.class, () -> new PaymentOperationalSummaryPayment(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                BigDecimal.ONE, "sar", Instant.parse("2026-08-13T00:00:00Z")));
    }
}
