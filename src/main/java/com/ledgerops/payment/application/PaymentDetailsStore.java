package com.ledgerops.payment.application;

import com.ledgerops.payment.api.PaymentDetailsSnapshot;

import java.util.Optional;
import java.util.UUID;

public interface PaymentDetailsStore {

    Optional<PaymentDetailsSnapshot> findByTenantAndPayment(UUID tenantId, UUID paymentId);
}
