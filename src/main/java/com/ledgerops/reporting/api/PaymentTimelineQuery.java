package com.ledgerops.reporting.api;

import java.util.List;
import java.util.UUID;

public interface PaymentTimelineQuery {

    List<PaymentTimelineEntry> findByTenantAndPayment(UUID tenantId, UUID paymentId);
}
