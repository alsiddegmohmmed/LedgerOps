package com.ledgerops.payment.api;

import java.util.Optional;
import java.util.UUID;

public interface ReversalDetailsQuery {

    Optional<ReversalDetailsSnapshot> findByTenantAndPayment(UUID tenantId, UUID paymentId);
}
