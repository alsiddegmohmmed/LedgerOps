package com.ledgerops.audit.api;

import java.util.UUID;

public interface AuditAppendPort {

    void appendPaymentCreated(
            String actorIssuer,
            String actorSubject,
            String principalType,
            UUID tenantId,
            UUID paymentId,
            String correlationId
    );
}
