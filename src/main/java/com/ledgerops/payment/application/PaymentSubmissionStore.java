package com.ledgerops.payment.application;

import com.ledgerops.payment.domain.Payment;
import com.ledgerops.payment.domain.PaymentAttempt;
import com.ledgerops.payment.domain.AttemptSubjectType;
import com.ledgerops.payment.domain.PaymentId;

import java.util.Optional;
import java.util.UUID;

public interface PaymentSubmissionStore {

    Optional<VersionedPayment> lockByTenantAndId(UUID tenantId, PaymentId paymentId);

    Optional<PaymentAttempt> findAttempt(UUID tenantId, PaymentId paymentId, int sequence);

    Optional<PaymentAttempt> findAttempt(
            UUID tenantId,
            PaymentId paymentId,
            AttemptSubjectType subjectType,
            UUID subjectId,
            int sequence
    );

    void insertAttempt(PaymentAttempt attempt);

    boolean compareAndSet(Payment updatedPayment, long expectedVersion);
}
