package com.ledgerops.payment.application;

import com.ledgerops.merchant.api.MerchantReference;
import com.ledgerops.payment.domain.CustomerId;
import com.ledgerops.payment.domain.IdempotencyKey;
import com.ledgerops.payment.domain.Money;
import com.ledgerops.payment.domain.Payment;
import com.ledgerops.payment.domain.PaymentId;
import com.ledgerops.payment.domain.PaymentMethodCategory;
import com.ledgerops.payment.domain.PaymentStatus;
import com.ledgerops.payment.domain.Reversal;
import com.ledgerops.payment.domain.ReversalId;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReversalLifecycleEventFactoryTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test
    void serializesReversalAmountAsTheStringRequiredByTheEventContract() throws Exception {
        Reversal reversal = Reversal.request(
                ReversalId.newId(),
                completedPayment(),
                UUID.randomUUID(),
                "Customer requested a full reversal",
                Instant.parse("2026-08-10T12:00:00Z")
        );

        JsonNode payload = JSON.readTree(ReversalLifecycleEventFactory.requested(
                reversal,
                UUID.randomUUID(),
                reversal.id().value(),
                Instant.parse("2026-08-10T12:00:00Z")
        ).canonicalPayloadJson());

        assertTrue(payload.get("amount").isString());
        assertEquals("125.00", payload.get("amount").asString());
    }

    private Payment completedPayment() {
        UUID tenantId = UUID.randomUUID();
        return Payment.rehydrate(
                PaymentId.newId(),
                MerchantReference.from(tenantId, UUID.randomUUID()),
                CustomerId.from(UUID.randomUUID()),
                Money.of(new BigDecimal("125.00"), Currency.getInstance("USD")),
                PaymentMethodCategory.from("CARD"),
                IdempotencyKey.from("reversal-event-" + UUID.randomUUID()),
                PaymentStatus.COMPLETED
        );
    }
}
