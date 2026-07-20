package com.ledgerops.payment.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ledgerops.merchant.api.MerchantReference;
import com.ledgerops.payment.application.PaymentCreationStore;
import com.ledgerops.payment.application.PaymentLifecycleStore;
import com.ledgerops.payment.application.VersionedPayment;
import com.ledgerops.payment.domain.CustomerId;
import com.ledgerops.payment.domain.IdempotencyKey;
import com.ledgerops.payment.domain.Money;
import com.ledgerops.payment.domain.Payment;
import com.ledgerops.payment.domain.PaymentId;
import com.ledgerops.payment.domain.PaymentMethodCategory;
import com.ledgerops.payment.domain.PaymentStatus;
import com.ledgerops.support.PostgresTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
class PaymentLifecyclePersistenceIntegrationTests {

    @Autowired
    private PaymentCreationStore creationStore;

    @Autowired
    private PaymentLifecycleStore lifecycleStore;

    @Test
    void loadsPaymentWithItsPersistenceVersionInsideTenantScope() {
        Payment payment = persistCreatedPayment();

        VersionedPayment loaded = lifecycleStore.findByTenantAndId(
                payment.tenantId(),
                payment.id()
        ).orElseThrow();

        assertEquals(payment.id(), loaded.payment().id());
        assertEquals(payment.tenantId(), loaded.payment().tenantId());
        assertEquals(PaymentStatus.CREATED, loaded.payment().status());
        assertEquals(0, loaded.version());
        assertFalse(
                lifecycleStore.findByTenantAndId(UUID.randomUUID(), payment.id()).isPresent()
        );
    }

    @Test
    void compareAndSetPersistsTheDomainTransitionAndIncrementsVersionOnce() {
        Payment payment = persistCreatedPayment();
        VersionedPayment loaded = lifecycleStore.findByTenantAndId(
                payment.tenantId(),
                payment.id()
        ).orElseThrow();

        boolean updated = lifecycleStore.compareAndSet(
                loaded.payment().startValidation(),
                loaded.version()
        );
        VersionedPayment persisted = lifecycleStore.findByTenantAndId(
                payment.tenantId(),
                payment.id()
        ).orElseThrow();

        assertTrue(updated);
        assertEquals(PaymentStatus.VALIDATING, persisted.payment().status());
        assertEquals(1, persisted.version());
        assertEquals(payment.merchantReference(), persisted.payment().merchantReference());
        assertEquals(payment.customerId(), persisted.payment().customerId());
        assertEquals(payment.amount(), persisted.payment().amount());
        assertEquals(payment.idempotencyKey(), persisted.payment().idempotencyKey());
    }

    @Test
    void staleVersionCannotOverwriteTheCommittedWinner() {
        Payment payment = persistCreatedPayment();
        VersionedPayment firstReader = lifecycleStore.findByTenantAndId(
                payment.tenantId(),
                payment.id()
        ).orElseThrow();
        VersionedPayment staleReader = lifecycleStore.findByTenantAndId(
                payment.tenantId(),
                payment.id()
        ).orElseThrow();

        assertTrue(lifecycleStore.compareAndSet(
                firstReader.payment().startValidation(),
                firstReader.version()
        ));
        assertFalse(lifecycleStore.compareAndSet(
                staleReader.payment().startValidation(),
                staleReader.version()
        ));

        VersionedPayment persisted = lifecycleStore.findByTenantAndId(
                payment.tenantId(),
                payment.id()
        ).orElseThrow();
        assertEquals(PaymentStatus.VALIDATING, persisted.payment().status());
        assertEquals(1, persisted.version());
    }

    @Test
    void coordinatedConcurrentWritersProduceExactlyOneVersionIncrement() throws Exception {
        Payment payment = persistCreatedPayment();
        VersionedPayment snapshot = lifecycleStore.findByTenantAndId(
                payment.tenantId(),
                payment.id()
        ).orElseThrow();
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<Boolean> first = executor.submit(() -> {
                barrier.await();
                return lifecycleStore.compareAndSet(
                        snapshot.payment().startValidation(),
                        snapshot.version()
                );
            });
            Future<Boolean> second = executor.submit(() -> {
                barrier.await();
                return lifecycleStore.compareAndSet(
                        snapshot.payment().startValidation(),
                        snapshot.version()
                );
            });

            int successfulUpdates = (first.get() ? 1 : 0) + (second.get() ? 1 : 0);
            VersionedPayment persisted = lifecycleStore.findByTenantAndId(
                    payment.tenantId(),
                    payment.id()
            ).orElseThrow();

            assertEquals(1, successfulUpdates);
            assertEquals(PaymentStatus.VALIDATING, persisted.payment().status());
            assertEquals(1, persisted.version());
        } finally {
            executor.shutdownNow();
        }
    }

    private Payment persistCreatedPayment() {
        UUID tenantId = UUID.randomUUID();
        Payment payment = Payment.create(
                PaymentId.newId(),
                MerchantReference.from(tenantId, UUID.randomUUID()),
                CustomerId.from(UUID.randomUUID()),
                Money.of(new BigDecimal("125.00"), Currency.getInstance("SAR")),
                PaymentMethodCategory.from("card"),
                IdempotencyKey.from("lifecycle-" + UUID.randomUUID())
        );
        creationStore.insertOrFind(payment, "a".repeat(64));
        return payment;
    }
}
