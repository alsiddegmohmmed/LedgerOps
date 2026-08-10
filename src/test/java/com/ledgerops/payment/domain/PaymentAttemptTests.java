package com.ledgerops.payment.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;

class PaymentAttemptTests {

    @Test
    void requiresAPositiveSequenceAndSimulatorProvider() {
        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> attempt(0)
        );

        assertEquals("Payment Attempt sequence must be positive", exception.getMessage());
        assertEquals(ProviderId.SIMULATOR, attempt(1).providerId());
    }

    @Test
    void rejectsAProviderKeyNotDerivedFromPaymentAndZeroAmount() {
        PaymentAttempt valid = attempt(1);

        assertThrows(IllegalArgumentException.class, () -> new PaymentAttempt(
                valid.attemptId(), valid.tenantId(), valid.paymentId(), valid.sequence(),
                valid.providerId(), "wrong", valid.initiatedAt(), valid.merchantId(),
                valid.customerId(), valid.amount(), valid.paymentMethodCategory(),
                valid.requestIntentHash()
        ));
        assertThrows(IllegalArgumentException.class, () -> new PaymentAttempt(
                valid.attemptId(), valid.tenantId(), valid.paymentId(), valid.sequence(),
                valid.providerId(), valid.providerIdempotencyKey(), valid.initiatedAt(),
                valid.merchantId(), valid.customerId(),
                Money.of(BigDecimal.ZERO, Currency.getInstance("SAR")),
                valid.paymentMethodCategory(), valid.requestIntentHash()
        ));
    }

    @Test
    void supportsAReversalSubjectWithoutIntroducingASecondAttemptType() {
        UUID paymentId = UUID.randomUUID();
        UUID reversalId = UUID.randomUUID();

        PaymentAttempt attempt = new PaymentAttempt(
                PaymentAttemptId.from(UUID.randomUUID()),
                UUID.randomUUID(),
                PaymentId.from(paymentId),
                AttemptSubjectType.REVERSAL,
                reversalId,
                1,
                ProviderId.SIMULATOR,
                "reversal:" + reversalId,
                Instant.parse("2026-07-21T12:00:00Z"),
                UUID.randomUUID(),
                CustomerId.from(UUID.randomUUID()),
                Money.of(new BigDecimal("1.00"), Currency.getInstance("SAR")),
                PaymentMethodCategory.from("CARD"),
                "b".repeat(64)
        );

        assertEquals(AttemptSubjectType.REVERSAL, attempt.subjectType());
        assertEquals(reversalId, attempt.subjectId());
        assertEquals(paymentId, attempt.paymentId().value());
    }

    private PaymentAttempt attempt(int sequence) {
        UUID paymentId = UUID.randomUUID();
        return new PaymentAttempt(
                PaymentAttemptId.from(UUID.randomUUID()),
                UUID.randomUUID(),
                PaymentId.from(paymentId),
                sequence,
                ProviderId.SIMULATOR,
                "payment:" + paymentId,
                Instant.parse("2026-07-21T12:00:00Z"),
                UUID.randomUUID(),
                CustomerId.from(UUID.randomUUID()),
                Money.of(new BigDecimal("1.00"), Currency.getInstance("SAR")),
                PaymentMethodCategory.from("CARD"),
                "a".repeat(64)
        );
    }
}
