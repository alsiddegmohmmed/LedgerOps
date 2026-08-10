package com.ledgerops.risk.api;

import java.util.Optional;
import java.util.UUID;

public interface RiskPaymentQuery {

    Optional<RiskPaymentSnapshot> findSnapshotByTenantAndPayment(UUID tenantId, UUID paymentId);
}
