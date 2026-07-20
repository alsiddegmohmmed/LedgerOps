package com.ledgerops.payment.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.ledgerops.merchant.api.MerchantReference;
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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
class PaymentValidationStartIntegrationTests {

    @Autowired
    private PaymentValidationStartService validationStartService;

    @Autowired
    private PaymentCreationStore creationStore;

    @Autowired
    private PaymentLifecycleStore lifecycleStore;

    @Test
    void transactionOneCommitsCreatedToValidatingWithOneVersionIncrement() {
        Payment payment = persistCreatedPayment();

        VersionedPayment result = validationStartService.start(
                payment.tenantId(),
                payment.id()
        );
        VersionedPayment persisted = lifecycleStore.findByTenantAndId(
                payment.tenantId(),
                payment.id()
        ).orElseThrow();

        assertEquals(PaymentStatus.VALIDATING, result.payment().status());
        assertEquals(1, result.version());
        assertEquals(PaymentStatus.VALIDATING, persisted.payment().status());
        assertEquals(1, persisted.version());
    }

    @Test
    void invalidRepeatLeavesTheCommittedValidatingPaymentUnchanged() {
        Payment payment = persistCreatedPayment();
        validationStartService.start(payment.tenantId(), payment.id());

        PaymentLifecycleStateException exception = assertThrows(
                PaymentLifecycleStateException.class,
                () -> validationStartService.start(payment.tenantId(), payment.id())
        );
        VersionedPayment persisted = lifecycleStore.findByTenantAndId(
                payment.tenantId(),
                payment.id()
        ).orElseThrow();

        assertEquals(PaymentStatus.VALIDATING, exception.actualStatus());
        assertEquals(PaymentStatus.VALIDATING, persisted.payment().status());
        assertEquals(1, persisted.version());
    }

    @Test
    void concurrentStartsCommitExactlyOneTransitionAndTypeTheLosingOutcome() throws Exception {
        Payment payment = persistCreatedPayment();
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<VersionedPayment> first = executor.submit(() -> {
                barrier.await();
                return validationStartService.start(payment.tenantId(), payment.id());
            });
            Future<VersionedPayment> second = executor.submit(() -> {
                barrier.await();
                return validationStartService.start(payment.tenantId(), payment.id());
            });

            int successes = 0;
            RuntimeException losingFailure = null;
            for (Future<VersionedPayment> future : java.util.List.of(first, second)) {
                try {
                    future.get();
                    successes++;
                } catch (ExecutionException exception) {
                    losingFailure = (RuntimeException) exception.getCause();
                }
            }

            assertEquals(1, successes);
            if (losingFailure instanceof PaymentLifecycleStateException) {
                assertInstanceOf(PaymentLifecycleStateException.class, losingFailure);
            } else {
                assertInstanceOf(PaymentOptimisticConcurrencyException.class, losingFailure);
            }
            VersionedPayment persisted = lifecycleStore.findByTenantAndId(
                    payment.tenantId(),
                    payment.id()
            ).orElseThrow();
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
                IdempotencyKey.from("validation-start-" + UUID.randomUUID())
        );
        creationStore.insertOrFind(payment, "b".repeat(64));
        return payment;
    }
}
