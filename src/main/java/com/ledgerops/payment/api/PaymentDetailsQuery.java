package com.ledgerops.payment.api;

import java.util.Optional;
import java.util.UUID;

public interface PaymentDetailsQuery {

    Optional<PaymentDetailsSnapshot> findByTenantAndPayment(UUID tenantId, UUID paymentId);
}
