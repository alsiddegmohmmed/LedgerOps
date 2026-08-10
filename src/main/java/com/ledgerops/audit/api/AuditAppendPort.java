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

    void appendIdentityInvitationRevoked(
            String actorIssuer,
            String actorSubject,
            UUID tenantId,
            UUID membershipId,
            UUID invitationId,
            String reason,
            String correlationId
    );

    void appendIdentityInvitationCreated(
            String actorIssuer,
            String actorSubject,
            UUID tenantId,
            UUID membershipId,
            UUID invitationId,
            String reason,
            String correlationId
    );

    void appendIdentityInvitationReinvited(
            String actorIssuer,
            String actorSubject,
            UUID tenantId,
            UUID previousMembershipId,
            UUID membershipId,
            UUID invitationId,
            String reason,
            String correlationId
    );

    void appendIdentityMembershipRolesChanged(
            String actorIssuer,
            String actorSubject,
            UUID tenantId,
            UUID membershipId,
            String reason,
            String correlationId
    );

    void appendSupportSessionStarted(
            String actorIssuer,
            String actorSubject,
            UUID tenantId,
            UUID supportSessionId,
            String reason,
            java.time.Instant startedAt,
            java.time.Instant expiresAt,
            String correlationId
    );

    default void appendSupportSessionRead(
            String actorIssuer,
            String actorSubject,
            UUID tenantId,
            UUID supportSessionId,
            String resourcePath,
            String correlationId
    ) {
        // Test doubles may ignore support-read evidence; the production adapter records it.
    }

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

    default void appendMerchantLifecycleChanged(
            String actorIssuer,
            String actorSubject,
            UUID tenantId,
            UUID merchantId,
            String previousStatus,
            String status,
            String reason,
            String correlationId
    ) {
        appendMerchantLifecycleChanged(
                actorIssuer, actorSubject, tenantId, merchantId,
                previousStatus, status, correlationId);
    }

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
            String reason,
            String correlationId
    );

    void appendOperationalContactChanged(
            String actorIssuer,
            String actorSubject,
            UUID tenantId,
            UUID contactId,
            long version,
            String reason,
            String correlationId
    );

    void appendCredentialProvisioned(
            String actorIssuer,
            String actorSubject,
            UUID tenantId,
            UUID merchantId,
            UUID credentialId,
            UUID operationId,
            String reason,
            String correlationId
    );

    void appendCredentialRotated(
            String actorIssuer,
            String actorSubject,
            UUID tenantId,
            UUID merchantId,
            UUID previousCredentialId,
            UUID replacementCredentialId,
            String reason,
            String correlationId
    );

    void appendCredentialRevoked(
            String actorIssuer,
            String actorSubject,
            UUID tenantId,
            UUID merchantId,
            UUID credentialId,
            String reason,
            String correlationId
    );
}
