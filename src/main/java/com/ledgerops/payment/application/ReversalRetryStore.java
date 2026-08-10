package com.ledgerops.payment.application;

import com.ledgerops.payment.domain.PaymentAttempt;
import com.ledgerops.payment.domain.PaymentId;
import com.ledgerops.payment.domain.ReversalId;

import java.util.Optional;
import java.util.UUID;

public interface ReversalRetryStore extends ReversalStore {

    Optional<PaymentAttempt> findAttemptById(
            UUID tenantId,
            PaymentId paymentId,
            UUID attemptId
    );

    Optional<PaymentAttempt> findLatestReversalAttempt(
            UUID tenantId,
            PaymentId paymentId,
            ReversalId reversalId
    );

    void insertAttempt(PaymentAttempt attempt);

    Optional<ReversalRetryApplication> findRetryApplication(
            UUID tenantId,
            ReversalId reversalId,
            UUID previousAttemptId
    );

    void insertRetryApplication(ReversalRetryApplication application);
}
