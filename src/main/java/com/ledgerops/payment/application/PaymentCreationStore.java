package com.ledgerops.payment.application;

import com.ledgerops.payment.domain.IdempotencyKey;
import com.ledgerops.payment.domain.Payment;

import java.util.Optional;
import java.util.UUID;

public interface PaymentCreationStore {

    Optional<StoredPayment> findByTenantAndIdempotencyKey(
            UUID tenantId,
            IdempotencyKey idempotencyKey
    );

    StoredPayment insertOrFind(Payment payment, String requestFingerprint);
}
