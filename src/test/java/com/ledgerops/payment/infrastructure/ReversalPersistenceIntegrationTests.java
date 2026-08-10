package com.ledgerops.payment.infrastructure;

import com.ledgerops.merchant.api.MerchantReference;
import com.ledgerops.payment.application.PaymentCreationStore;
import com.ledgerops.payment.application.ReversalStore;
import com.ledgerops.payment.domain.CustomerId;
import com.ledgerops.payment.domain.IdempotencyKey;
import com.ledgerops.payment.domain.Money;
import com.ledgerops.payment.domain.Payment;
import com.ledgerops.payment.domain.PaymentId;
import com.ledgerops.payment.domain.PaymentMethodCategory;
import com.ledgerops.payment.domain.PaymentStatus;
import com.ledgerops.payment.domain.Reversal;
import com.ledgerops.payment.domain.ReversalId;
import com.ledgerops.payment.domain.ReversalStatus;
import com.ledgerops.support.PostgresTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
class ReversalPersistenceIntegrationTests {

    private static final Instant REQUESTED_AT = Instant.parse("2026-08-10T12:00:00Z");

    @Autowired
    private PaymentCreationStore payments;

    @Autowired
    private ReversalStore reversals;

    @Test
    void persistsRequestedReversalAndOptimisticLifecycleVersion() {
        Payment payment = completedPayment();
        payments.insertOrFind(payment, "a".repeat(64));

        Reversal requested = Reversal.request(
                ReversalId.newId(),
                payment,
                UUID.randomUUID(),
                "Customer requested a full reversal",
                REQUESTED_AT
        );
        reversals.insert(requested);

        Reversal loaded = reversals.findByTenantAndPayment(
                payment.tenantId(), payment.id()
        ).orElseThrow();
        assertEquals(requested.id(), loaded.id());
        assertEquals(ReversalStatus.REQUESTED, loaded.status());
        assertEquals(payment.amount(), loaded.amount());
        assertEquals(0, loaded.version());

        Reversal processing = loaded.startProcessing(REQUESTED_AT.plusSeconds(1));
        assertTrue(reversals.compareAndSet(processing, loaded.version()));

        Reversal updated = reversals.lockByTenantAndId(
                payment.tenantId(), requested.id()
        ).orElseThrow();
        assertEquals(ReversalStatus.PROCESSING, updated.status());
        assertEquals(1, updated.version());
        assertEquals(REQUESTED_AT.plusSeconds(1), updated.processingAt());
    }

    private Payment completedPayment() {
        UUID tenantId = UUID.randomUUID();
        return Payment.rehydrate(
                PaymentId.newId(),
                MerchantReference.from(tenantId, UUID.randomUUID()),
                CustomerId.from(UUID.randomUUID()),
                Money.of(new BigDecimal("125.00"), Currency.getInstance("SAR")),
                PaymentMethodCategory.from("CARD"),
                IdempotencyKey.from("reversal-persistence-" + UUID.randomUUID()),
                PaymentStatus.COMPLETED
        );
    }
}
