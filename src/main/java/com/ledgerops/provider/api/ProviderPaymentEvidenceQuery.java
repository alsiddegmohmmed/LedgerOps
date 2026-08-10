package com.ledgerops.provider.api;

import java.util.List;
import java.util.UUID;

public interface ProviderPaymentEvidenceQuery {

    List<ProviderEvidence> findByTenantAndPayment(UUID tenantId, UUID paymentId);
}
