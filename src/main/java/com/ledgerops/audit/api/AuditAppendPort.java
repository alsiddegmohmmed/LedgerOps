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

    void appendIdentityMembershipAccepted(
            String actorIssuer,
            String actorSubject,
            UUID tenantId,
            UUID membershipId,
            UUID applicationUserId,
            String correlationId
    );

    void appendTenantOnboarded(
            String actorIssuer,
            String actorSubject,
            UUID tenantId,
            UUID merchantId,
            UUID membershipId,
            UUID invitationId,
            String correlationId
    );

    void appendMerchantLifecycleChanged(
            String actorIssuer,
            String actorSubject,
            UUID tenantId,
            UUID merchantId,
            String previousStatus,
            String status,
            String correlationId
    );

    void appendTenantLifecycleChanged(
            String actorIssuer,
            String actorSubject,
            UUID tenantId,
            String previousStatus,
            String status,
            String correlationId
    );

    void appendTenantConfigurationChanged(
            String actorIssuer,
            String actorSubject,
            UUID tenantId,
            long version,
            String correlationId
    );

    void appendOperationalContactChanged(
            String actorIssuer,
            String actorSubject,
            UUID tenantId,
            UUID contactId,
            long version,
            String correlationId
    );
}
