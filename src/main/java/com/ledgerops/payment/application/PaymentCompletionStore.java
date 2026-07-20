package com.ledgerops.payment.application;

import com.ledgerops.payment.domain.Payment;
import com.ledgerops.payment.domain.PaymentId;

import java.util.Optional;
import java.util.UUID;

public interface PaymentCompletionStore {

    Optional<VersionedPayment> lockByTenantAndId(
            UUID tenantId,
            PaymentId paymentId
    );

    boolean compareAndSet(Payment updatedPayment, long expectedVersion);
}
