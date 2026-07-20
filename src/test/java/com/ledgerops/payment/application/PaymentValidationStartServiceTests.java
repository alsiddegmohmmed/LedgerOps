package com.ledgerops.payment.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ledgerops.merchant.api.MerchantReference;
import com.ledgerops.payment.domain.CustomerId;
import com.ledgerops.payment.domain.IdempotencyKey;
import com.ledgerops.payment.domain.Money;
import com.ledgerops.payment.domain.Payment;
import com.ledgerops.payment.domain.PaymentId;
import com.ledgerops.payment.domain.PaymentMethodCategory;
import com.ledgerops.payment.domain.PaymentStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Currency;
import java.util.Optional;
import java.util.UUID;

class PaymentValidationStartServiceTests {

    @Test
    void transitionsCreatedPaymentUsingItsExpectedPersistenceVersion() {
        Payment payment = payment(PaymentStatus.CREATED);
        StubLifecycleStore store = new StubLifecycleStore(new VersionedPayment(payment, 7));

        VersionedPayment result = new PaymentValidationStartService(store).start(
                payment.tenantId(),
                payment.id()
        );

        assertEquals(PaymentStatus.VALIDATING, result.payment().status());
        assertEquals(8, result.version());
        assertEquals(7, store.comparedVersion);
        assertEquals(PaymentStatus.VALIDATING, store.comparedPayment.status());
    }

    @Test
    void missingPaymentProducesATypedTenantScopedFailure() {
        UUID tenantId = UUID.randomUUID();
        PaymentId paymentId = PaymentId.newId();
        StubLifecycleStore store = new StubLifecycleStore(null);

        PaymentLifecycleNotFoundException exception = assertThrows(
                PaymentLifecycleNotFoundException.class,
                () -> new PaymentValidationStartService(store).start(tenantId, paymentId)
        );

        assertEquals(tenantId, exception.tenantId());
        assertEquals(paymentId, exception.paymentId());
        assertFalse(store.compareCalled);
    }

    @Test
    void nonCreatedPaymentProducesATypedStateFailureWithoutAnUpdate() {
        Payment payment = payment(PaymentStatus.VALIDATING);
        StubLifecycleStore store = new StubLifecycleStore(new VersionedPayment(payment, 1));

        PaymentLifecycleStateException exception = assertThrows(
                PaymentLifecycleStateException.class,
                () -> new PaymentValidationStartService(store).start(
                        payment.tenantId(),
                        payment.id()
                )
        );

        assertEquals(payment.id(), exception.paymentId());
        assertEquals(PaymentStatus.CREATED, exception.requiredStatus());
        assertEquals(PaymentStatus.VALIDATING, exception.actualStatus());
        assertFalse(store.compareCalled);
    }

    @Test
    void failedCompareAndSetProducesATypedConcurrencyFailure() {
        Payment payment = payment(PaymentStatus.CREATED);
        StubLifecycleStore store = new StubLifecycleStore(new VersionedPayment(payment, 4));
        store.compareResult = false;

        PaymentOptimisticConcurrencyException exception = assertThrows(
                PaymentOptimisticConcurrencyException.class,
                () -> new PaymentValidationStartService(store).start(
                        payment.tenantId(),
                        payment.id()
                )
        );

        assertEquals(payment.id(), exception.paymentId());
        assertEquals(4, exception.expectedVersion());
        assertTrue(store.compareCalled);
    }

    private Payment payment(PaymentStatus status) {
        UUID tenantId = UUID.randomUUID();
        Payment created = Payment.create(
                PaymentId.newId(),
                MerchantReference.from(tenantId, UUID.randomUUID()),
                CustomerId.from(UUID.randomUUID()),
                Money.of(new BigDecimal("125.00"), Currency.getInstance("SAR")),
                PaymentMethodCategory.from("card"),
                IdempotencyKey.from("validation-" + UUID.randomUUID())
        );

        return switch (status) {
            case CREATED -> created;
            case VALIDATING -> created.startValidation();
            default -> throw new IllegalArgumentException("Unsupported test status: " + status);
        };
    }

    private static final class StubLifecycleStore implements PaymentLifecycleStore {

        private final VersionedPayment stored;
        private boolean compareResult = true;
        private boolean compareCalled;
        private Payment comparedPayment;
        private long comparedVersion;

        private StubLifecycleStore(VersionedPayment stored) {
            this.stored = stored;
        }

        @Override
        public Optional<VersionedPayment> findByTenantAndId(
                UUID tenantId,
                PaymentId paymentId
        ) {
            return Optional.ofNullable(stored)
                    .filter(versioned -> versioned.payment().tenantId().equals(tenantId))
                    .filter(versioned -> versioned.payment().id().equals(paymentId));
        }

        @Override
        public boolean compareAndSet(Payment updatedPayment, long expectedVersion) {
            compareCalled = true;
            comparedPayment = updatedPayment;
            comparedVersion = expectedVersion;
            return compareResult;
        }
    }
}
