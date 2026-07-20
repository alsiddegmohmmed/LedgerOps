package com.ledgerops.payment.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ledgerops.merchant.api.MerchantReference;
import com.ledgerops.payment.application.PaymentCompletionStore;
import com.ledgerops.payment.application.PaymentCreationStore;
import com.ledgerops.payment.application.VersionedPayment;
import com.ledgerops.payment.domain.CustomerId;
import com.ledgerops.payment.domain.IdempotencyKey;
import com.ledgerops.payment.domain.Money;
import com.ledgerops.payment.domain.Payment;
import com.ledgerops.payment.domain.PaymentId;
import com.ledgerops.payment.domain.PaymentMethodCategory;
import com.ledgerops.support.PostgresTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
class PaymentCompletionPersistenceIntegrationTests {

    @Autowired
    private PaymentCreationStore creationStore;

    @Autowired
    private PaymentCompletionStore completionStore;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Test
    void locksAndLoadsTheVersionedPaymentInsideTenantScope() {
        Payment payment = persistPayment();

        VersionedPayment locked = transactionTemplate.execute(status ->
                completionStore.lockByTenantAndId(
                        payment.tenantId(),
                        payment.id()
                ).orElseThrow()
        );
        boolean visibleToAnotherTenant = transactionTemplate.execute(status ->
                completionStore.lockByTenantAndId(
                        UUID.randomUUID(),
                        payment.id()
                ).isPresent()
        );

        assertEquals(payment.id(), locked.payment().id());
        assertEquals(payment.tenantId(), locked.payment().tenantId());
        assertEquals(0, locked.version());
        assertFalse(visibleToAnotherTenant);
    }

    @Test
    void concurrentCompletionTransactionsSerializeOnThePaymentRow() throws Exception {
        Payment payment = persistPayment();
        CountDownLatch firstLocked = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        CountDownLatch secondLocked = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> first = executor.submit(() -> transactionTemplate.executeWithoutResult(
                    status -> {
                        completionStore.lockByTenantAndId(
                                payment.tenantId(),
                                payment.id()
                        ).orElseThrow();
                        firstLocked.countDown();
                        await(releaseFirst);
                    }
            ));
            assertTrue(firstLocked.await(5, TimeUnit.SECONDS));

            Future<?> second = executor.submit(() -> transactionTemplate.executeWithoutResult(
                    status -> {
                        secondStarted.countDown();
                        completionStore.lockByTenantAndId(
                                payment.tenantId(),
                                payment.id()
                        ).orElseThrow();
                        secondLocked.countDown();
                    }
            ));
            assertTrue(secondStarted.await(5, TimeUnit.SECONDS));
            assertFalse(secondLocked.await(250, TimeUnit.MILLISECONDS));

            releaseFirst.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
            assertTrue(secondLocked.await(1, TimeUnit.SECONDS));
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
        }
    }

    private Payment persistPayment() {
        UUID tenantId = UUID.randomUUID();
        Payment payment = Payment.create(
                PaymentId.newId(),
                MerchantReference.from(tenantId, UUID.randomUUID()),
                CustomerId.from(UUID.randomUUID()),
                Money.of(new BigDecimal("125.00"), Currency.getInstance("SAR")),
                PaymentMethodCategory.from("card"),
                IdempotencyKey.from("completion-lock-" + UUID.randomUUID())
        );
        creationStore.insertOrFind(payment, "a".repeat(64));
        return payment;
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out while coordinating lock test");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Lock test was interrupted", exception);
        }
    }
}
