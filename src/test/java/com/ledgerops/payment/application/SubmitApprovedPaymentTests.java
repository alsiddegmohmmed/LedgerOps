package com.ledgerops.payment.application;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ledgerops.merchant.api.MerchantReference;
import com.ledgerops.messaging.api.MessageOutbox;
import com.ledgerops.payment.domain.CustomerId;
import com.ledgerops.payment.domain.IdempotencyKey;
import com.ledgerops.payment.domain.Money;
import com.ledgerops.payment.domain.Payment;
import com.ledgerops.payment.domain.PaymentId;
import com.ledgerops.payment.domain.PaymentMethodCategory;
import com.ledgerops.payment.domain.PaymentStatus;
import com.ledgerops.tenancy.api.TenantActivityQuery;
import com.ledgerops.tenancy.api.TenantActivityStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.Optional;
import java.util.UUID;

class SubmitApprovedPaymentTests {

    @Test
    void compareAndSetConflictRaisesTypedConcurrencyFailureBeforeOutboxAppend() {
        PaymentSubmissionStore paymentStore = mock(PaymentSubmissionStore.class);
        MessageOutbox outbox = mock(MessageOutbox.class);
        TenantActivityQuery tenants = mock(TenantActivityQuery.class);
        Payment payment = approvedPayment();
        when(tenants.evaluateForUpdate(any())).thenReturn(TenantActivityStatus.ALLOWED);
        when(paymentStore.lockByTenantAndId(payment.tenantId(), payment.id()))
                .thenReturn(Optional.of(new VersionedPayment(payment, 7)));
        when(paymentStore.findAttempt(payment.tenantId(), payment.id(), 1))
                .thenReturn(Optional.empty());
        when(outbox.findByAggregate(any(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(outbox.find(any(), any())).thenReturn(Optional.empty());
        when(paymentStore.compareAndSet(any(), org.mockito.ArgumentMatchers.eq(7L)))
                .thenReturn(false);
        SubmitApprovedPayment service = new SubmitApprovedPayment(
                paymentStore,
                outbox,
                Clock.fixed(Instant.parse("2026-07-21T12:00:00Z"), ZoneOffset.UTC),
                new SimpleMeterRegistry(),
                tenants
        );

        assertThrows(
                PaymentOptimisticConcurrencyException.class,
                () -> service.submit(new SubmitApprovedPaymentCommand(
                        payment.tenantId(), payment.id(), UUID.randomUUID(), UUID.randomUUID()
                ))
        );

        verify(paymentStore).insertAttempt(any());
        verify(outbox, never()).appendOrGet(any());
    }

    private Payment approvedPayment() {
        UUID tenantId = UUID.randomUUID();
        Payment created = Payment.create(
                PaymentId.from(UUID.randomUUID()),
                MerchantReference.from(tenantId, UUID.randomUUID()),
                CustomerId.from(UUID.randomUUID()),
                Money.of(new BigDecimal("12.30"), Currency.getInstance("SAR")),
                PaymentMethodCategory.from("CARD"),
                IdempotencyKey.from("submission-unit-test")
        );
        return Payment.rehydrate(
                created.id(),
                created.merchantReference(),
                created.customerId(),
                created.amount(),
                created.paymentMethodCategory(),
                created.idempotencyKey(),
                PaymentStatus.APPROVED
        );
    }
}
