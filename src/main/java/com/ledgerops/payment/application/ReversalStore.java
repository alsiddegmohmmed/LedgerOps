package com.ledgerops.payment.application;

import com.ledgerops.payment.domain.PaymentId;
import com.ledgerops.payment.domain.Reversal;
import com.ledgerops.payment.domain.ReversalId;

import java.util.Optional;
import java.util.UUID;

public interface ReversalStore {

    Optional<Reversal> findByTenantAndPayment(UUID tenantId, PaymentId paymentId);

    Optional<Reversal> findByTenantAndId(UUID tenantId, ReversalId reversalId);

    Optional<Reversal> lockByTenantAndId(UUID tenantId, ReversalId reversalId);

    Reversal insert(Reversal reversal);

    boolean compareAndSet(Reversal updatedReversal, long expectedVersion);
}
