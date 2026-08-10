package com.ledgerops.payment.application;

import com.ledgerops.merchant.api.MerchantReference;
import com.ledgerops.payment.domain.CustomerId;
import com.ledgerops.payment.domain.IdempotencyKey;
import com.ledgerops.payment.domain.Money;
import com.ledgerops.payment.domain.Payment;
import com.ledgerops.payment.domain.PaymentId;
import com.ledgerops.payment.domain.PaymentMethodCategory;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Slice3PaymentContractTests {

    @Test
    void paymentCursorRoundTripsItsPositionAndFingerprint() {
        Instant createdAt = Instant.parse("2026-08-10T12:00:00Z");
        UUID paymentId = UUID.randomUUID();
        PaymentPageCursor cursor = new PaymentPageCursor(1, createdAt, paymentId, "a".repeat(64));

        assertEquals(cursor, PaymentPageCursorCodec.decode(PaymentPageCursorCodec.encode(cursor)));
    }

    @Test
    void paymentCursorRejectsMalformedAndOversizedValues() {
        assertThrows(InvalidPaymentCursorException.class, () -> PaymentPageCursorCodec.decode("not-a-cursor"));
        assertThrows(InvalidPaymentCursorException.class, () -> PaymentPageCursorCodec.decode("a".repeat(2049)));
    }

    @Test
    void lifecycleEventContainsTheTransitionAndStableDeduplicationIdentity() {
        Payment before = payment();
        Payment after = before.startValidation();
        UUID correlationId = UUID.randomUUID();
        UUID causationId = UUID.randomUUID();

        var draft = PaymentLifecycleEventFactory.draft(
                before, after, 1, "AUTOMATED", "VALIDATION_STARTED",
                correlationId, causationId, Instant.parse("2026-08-10T12:00:00Z"));

        assertEquals("PaymentLifecycleChanged", draft.messageType());
        assertEquals("ledgerops.payment.lifecycle.v1", draft.topic());
        assertEquals("payment-lifecycle:" + before.id().value() + ":1", draft.deduplicationKey());
        assertTrue(draft.canonicalPayloadJson().contains("\"fromStatus\":\"CREATED\""));
        assertTrue(draft.canonicalPayloadJson().contains("\"toStatus\":\"VALIDATING\""));
    }

    @Test
    void lifecycleEventCannotBeCreatedWithoutAStatusTransition() {
        Payment payment = payment();
        assertThrows(IllegalArgumentException.class, () -> PaymentLifecycleEventFactory.draft(
                payment, payment, 1, "AUTOMATED", "NO_CHANGE",
                UUID.randomUUID(), UUID.randomUUID(), Instant.now()));
    }

    private Payment payment() {
        UUID tenantId = UUID.randomUUID();
        return Payment.create(
                PaymentId.newId(),
                MerchantReference.from(tenantId, UUID.randomUUID()),
                CustomerId.from(UUID.randomUUID()),
                Money.of(new BigDecimal("10.00"), Currency.getInstance("SAR")),
                PaymentMethodCategory.from("CARD"),
                IdempotencyKey.from("slice-3-" + UUID.randomUUID()));
    }
}
