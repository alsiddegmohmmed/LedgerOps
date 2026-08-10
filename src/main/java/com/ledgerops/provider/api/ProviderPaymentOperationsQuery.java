package com.ledgerops.provider.api;

import java.util.Optional;
import java.util.UUID;

public interface ProviderPaymentOperationsQuery {

    Optional<ProviderPaymentOperations> findByTenantAndPayment(UUID tenantId, UUID paymentId);
}
