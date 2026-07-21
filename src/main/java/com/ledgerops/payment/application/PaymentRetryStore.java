package com.ledgerops.payment.application;

import com.ledgerops.payment.domain.PaymentAttempt;
import com.ledgerops.payment.domain.PaymentId;

import java.util.Optional;
import java.util.UUID;

public interface PaymentRetryStore extends PaymentSubmissionStore {
    Optional<PaymentAttempt> findAttemptById(UUID tenantId, PaymentId paymentId, UUID attemptId);
    Optional<PaymentAttempt> findLatestAttempt(UUID tenantId, PaymentId paymentId);
    Optional<PaymentRetryApplication> findRetryApplication(UUID tenantId, UUID retryRequestId);
    void insertRetryApplication(PaymentRetryApplication application);
}
