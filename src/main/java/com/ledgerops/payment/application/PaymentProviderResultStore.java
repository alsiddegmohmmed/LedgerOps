package com.ledgerops.payment.application;

import com.ledgerops.payment.domain.Payment;
import com.ledgerops.payment.domain.PaymentAttempt;
import com.ledgerops.payment.domain.PaymentId;

import java.util.Optional;
import java.util.UUID;

public interface PaymentProviderResultStore {

    Optional<VersionedPayment> lockByTenantAndId(UUID tenantId, PaymentId paymentId);

    Optional<PaymentAttempt> findAttemptById(
            UUID tenantId,
            PaymentId paymentId,
            UUID attemptId
    );

    Optional<AcceptedFinalProviderResult> findAcceptedFinalResult(
            UUID tenantId,
            PaymentId paymentId
    );

    void insertAcceptedFinalResult(AcceptedFinalProviderResult result);

    boolean compareAndSet(Payment updatedPayment, long expectedVersion);
}
