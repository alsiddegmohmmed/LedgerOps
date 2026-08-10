package com.ledgerops.audit.infrastructure;

import com.ledgerops.audit.domain.AuditActorIdentity;
import com.ledgerops.audit.domain.AuditActionType;
import com.ledgerops.audit.domain.AuditDetails;
import com.ledgerops.audit.domain.AuditPrincipalType;
import com.ledgerops.audit.domain.AuditReason;
import com.ledgerops.audit.domain.AuditRecord;
import com.ledgerops.audit.domain.AuditRecordId;
import com.ledgerops.audit.domain.AuditRecordRepository;
import com.ledgerops.audit.domain.AuditTargetType;
import com.ledgerops.audit.api.AuditAppendPort;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

@Repository
class AuditRecordPersistenceAdapter implements AuditRecordRepository, AuditAppendPort {

    private final SpringDataAuditRecordRepository repository;
    private final Clock clock;

    AuditRecordPersistenceAdapter(SpringDataAuditRecordRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    @Transactional
    public AuditRecord append(AuditRecord record) {
        AuditRecordJpaEntity entity = new AuditRecordJpaEntity(
                record.id().value(),
                record.actorIdentity().issuer(),
                record.actorIdentity().subject(),
                record.principalType().name(),
                record.tenantId(),
                record.actionType().value(),
                record.targetType().value(),
                record.targetId(),
                record.correlationId(),
                record.reason().value(),
                record.details().value(),
                record.occurredAt()
        );
        return toDomain(repository.saveAndFlush(entity));
    }

    @Override
    public void appendPaymentCreated(
            String actorIssuer,
            String actorSubject,
            String principalType,
            java.util.UUID tenantId,
            java.util.UUID paymentId,
            String correlationId
    ) {
        append(AuditRecord.create(
                AuditRecordId.newId(),
                new AuditActorIdentity(actorIssuer, actorSubject),
                AuditPrincipalType.valueOf(principalType),
                tenantId,
                new AuditActionType("payment.created", true),
                new AuditTargetType("payment"),
                paymentId.toString(),
                correlationId,
                new AuditReason("Payment creation"),
                AuditDetails.empty(),
                clock
        ));
    }

    @Override
    public void appendIdentityMembershipAccepted(
            String actorIssuer,
            String actorSubject,
            UUID tenantId,
            UUID membershipId,
            UUID applicationUserId,
            String correlationId
    ) {
        append(AuditRecord.create(
                AuditRecordId.newId(),
                new AuditActorIdentity(actorIssuer, actorSubject),
                AuditPrincipalType.HUMAN,
                tenantId,
                new AuditActionType("identity.membership.accepted", true),
                new AuditTargetType("tenant-membership"),
                membershipId.toString(),
                correlationId,
                new AuditReason("Invitation acceptance"),
                new AuditDetails("{\"applicationUserId\":\"" + applicationUserId + "\"}"),
                clock
        ));
    }

    @Override
    public void appendIdentityInvitationRevoked(
            String actorIssuer,
            String actorSubject,
            UUID tenantId,
            UUID membershipId,
            UUID invitationId,
            String reason,
            String correlationId
    ) {
        append(AuditRecord.create(
                AuditRecordId.newId(),
                new AuditActorIdentity(actorIssuer, actorSubject),
                AuditPrincipalType.HUMAN,
                tenantId,
                new AuditActionType("identity.membership.invitation-revoked", true),
                new AuditTargetType("tenant-membership"),
                membershipId.toString(),
                correlationId,
                new AuditReason(reason),
                new AuditDetails("{\"invitationId\":\"" + invitationId + "\"}"),
                clock
        ));
    }

    @Override
    public void appendIdentityInvitationCreated(
            String actorIssuer,
            String actorSubject,
            UUID tenantId,
            UUID membershipId,
            UUID invitationId,
            String reason,
            String correlationId
    ) {
        append(AuditRecord.create(
                AuditRecordId.newId(),
                new AuditActorIdentity(actorIssuer, actorSubject),
                AuditPrincipalType.HUMAN,
                tenantId,
                new AuditActionType("identity.membership.invitation-created", true),
                new AuditTargetType("tenant-membership"),
                membershipId.toString(),
                correlationId,
                new AuditReason(reason),
                new AuditDetails("{\"invitationId\":\"" + invitationId + "\"}"),
                clock
        ));
    }

    @Override
    public void appendIdentityInvitationReinvited(
            String actorIssuer,
            String actorSubject,
            UUID tenantId,
            UUID previousMembershipId,
            UUID membershipId,
            UUID invitationId,
            String reason,
            String correlationId
    ) {
        append(AuditRecord.create(
                AuditRecordId.newId(),
                new AuditActorIdentity(actorIssuer, actorSubject),
                AuditPrincipalType.HUMAN,
                tenantId,
                new AuditActionType("identity.membership.reinvited", true),
                new AuditTargetType("tenant-membership"),
                membershipId.toString(),
                correlationId,
                new AuditReason(reason),
                new AuditDetails("{\"previousMembershipId\":\"" + previousMembershipId
                        + "\",\"invitationId\":\"" + invitationId + "\"}"),
                clock
        ));
    }

    @Override
    public void appendIdentityMembershipRolesChanged(
            String actorIssuer,
            String actorSubject,
            UUID tenantId,
            UUID membershipId,
            String reason,
            String correlationId
    ) {
        append(AuditRecord.create(
                AuditRecordId.newId(),
                new AuditActorIdentity(actorIssuer, actorSubject),
                AuditPrincipalType.HUMAN,
                tenantId,
                new AuditActionType("identity.membership.roles-changed", true),
                new AuditTargetType("tenant-membership"),
                membershipId.toString(),
                correlationId,
                new AuditReason(reason),
                AuditDetails.empty(),
                clock
        ));
    }

    @Override
    public void appendSupportSessionStarted(
            String actorIssuer,
            String actorSubject,
            UUID tenantId,
            UUID supportSessionId,
            String reason,
            java.time.Instant startedAt,
            java.time.Instant expiresAt,
            String correlationId
    ) {
        append(AuditRecord.create(
                AuditRecordId.newId(),
                new AuditActorIdentity(actorIssuer, actorSubject),
                AuditPrincipalType.HUMAN,
                tenantId,
                new AuditActionType("identity.support.session-started", true),
                new AuditTargetType("support-session"),
                supportSessionId.toString(),
                correlationId,
                new AuditReason(reason),
                new AuditDetails("{\"startedAt\":\"" + startedAt
                        + "\",\"expiresAt\":\"" + expiresAt + "\"}"),
                clock
        ));
    }

    @Override
    public void appendSupportSessionRead(
            String actorIssuer,
            String actorSubject,
            UUID tenantId,
            UUID supportSessionId,
            String resourcePath,
            String correlationId
    ) {
        append(AuditRecord.create(
                AuditRecordId.newId(),
                new AuditActorIdentity(actorIssuer, actorSubject),
                AuditPrincipalType.HUMAN,
                tenantId,
                new AuditActionType("identity.support.session-read", true),
                new AuditTargetType("support-session"),
                supportSessionId.toString(),
                correlationId,
                new AuditReason("Support session read"),
                new AuditDetails("{\"resourcePath\":\""
                        + escapeJson(resourcePath) + "\"}"),
                clock
        ));
    }

    @Override
    public void appendTenantOnboarded(
            String actorIssuer,
            String actorSubject,
            UUID tenantId,
            UUID merchantId,
            UUID membershipId,
            UUID invitationId,
            String correlationId
    ) {
        append(AuditRecord.create(
                AuditRecordId.newId(),
                new AuditActorIdentity(actorIssuer, actorSubject),
                AuditPrincipalType.HUMAN,
                tenantId,
                new AuditActionType("tenant.onboarded", true),
                new AuditTargetType("tenant"),
                tenantId.toString(),
                correlationId,
                new AuditReason("Tenant onboarding"),
                new AuditDetails("{\"merchantId\":\"" + merchantId
                        + "\",\"membershipId\":\"" + membershipId
                        + "\",\"invitationId\":\"" + invitationId + "\"}"),
                clock
        ));
    }

    @Override
    public void appendMerchantLifecycleChanged(
            String actorIssuer,
            String actorSubject,
            UUID tenantId,
            UUID merchantId,
            String previousStatus,
            String status,
            String correlationId
    ) {
        appendMerchantLifecycleChanged(
                actorIssuer, actorSubject, tenantId, merchantId,
                previousStatus, status, "Merchant lifecycle change", correlationId);
    }

    @Override
    public void appendMerchantLifecycleChanged(
            String actorIssuer,
            String actorSubject,
            UUID tenantId,
            UUID merchantId,
            String previousStatus,
            String status,
            String reason,
            String correlationId
    ) {
        append(AuditRecord.create(
                AuditRecordId.newId(),
                new AuditActorIdentity(actorIssuer, actorSubject),
                AuditPrincipalType.HUMAN,
                tenantId,
                new AuditActionType(
                        "merchant." + status.toLowerCase(java.util.Locale.ROOT), true),
                new AuditTargetType("merchant"),
                merchantId.toString(),
                correlationId,
                new AuditReason(reason),
                new AuditDetails("{\"previousStatus\":\"" + previousStatus
                        + "\",\"status\":\"" + status + "\"}"),
                clock
        ));
    }

    @Override
    public void appendTenantLifecycleChanged(
            String actorIssuer,
            String actorSubject,
            UUID tenantId,
            String previousStatus,
            String status,
            String correlationId
    ) {
        append(AuditRecord.create(
                AuditRecordId.newId(),
                new AuditActorIdentity(actorIssuer, actorSubject),
                AuditPrincipalType.HUMAN,
                tenantId,
                new AuditActionType(
                        "tenant." + status.toLowerCase(java.util.Locale.ROOT), true),
                new AuditTargetType("tenant"),
                tenantId.toString(),
                correlationId,
                new AuditReason("Tenant lifecycle change"),
                new AuditDetails("{\"previousStatus\":\"" + previousStatus
                        + "\",\"status\":\"" + status + "\"}"),
                clock
        ));
    }

    @Override
    public void appendTenantConfigurationChanged(
            String actorIssuer,
            String actorSubject,
            UUID tenantId,
            long version,
            String reason,
            String correlationId
    ) {
        append(AuditRecord.create(
                AuditRecordId.newId(),
                new AuditActorIdentity(actorIssuer, actorSubject),
                AuditPrincipalType.HUMAN,
                tenantId,
                new AuditActionType("tenant.configuration.changed", true),
                new AuditTargetType("tenant-configuration"),
                tenantId + ":" + version,
                correlationId,
                new AuditReason(reason),
                new AuditDetails("{\"version\":" + version + "}"),
                clock
        ));
    }

    @Override
    public void appendOperationalContactChanged(
            String actorIssuer,
            String actorSubject,
            UUID tenantId,
            UUID contactId,
            long version,
            String reason,
            String correlationId
    ) {
        append(AuditRecord.create(
                AuditRecordId.newId(),
                new AuditActorIdentity(actorIssuer, actorSubject),
                AuditPrincipalType.HUMAN,
                tenantId,
                new AuditActionType("tenant.operational-contact.changed", true),
                new AuditTargetType("operational-contact"),
                contactId + ":" + version,
                correlationId,
                new AuditReason(reason),
                new AuditDetails("{\"contactId\":\"" + contactId
                        + "\",\"version\":" + version + "}"),
                clock
        ));
    }

    @Override
    public void appendCredentialProvisioned(
            String actorIssuer,
            String actorSubject,
            UUID tenantId,
            UUID merchantId,
            UUID credentialId,
            UUID operationId,
            String reason,
            String correlationId
    ) {
        append(AuditRecord.create(
                AuditRecordId.newId(),
                new AuditActorIdentity(actorIssuer, actorSubject),
                AuditPrincipalType.HUMAN,
                tenantId,
                new AuditActionType("identity.credential.provisioned", true),
                new AuditTargetType("service-credential"),
                credentialId.toString(),
                correlationId,
                new AuditReason(reason),
                new AuditDetails("{\"merchantId\":\"" + merchantId
                        + "\",\"operationId\":\"" + operationId + "\"}"),
                clock
        ));
    }

    @Override
    public void appendCredentialRotated(
            String actorIssuer,
            String actorSubject,
            UUID tenantId,
            UUID merchantId,
            UUID previousCredentialId,
            UUID replacementCredentialId,
            String reason,
            String correlationId
    ) {
        append(AuditRecord.create(
                AuditRecordId.newId(),
                new AuditActorIdentity(actorIssuer, actorSubject),
                AuditPrincipalType.HUMAN,
                tenantId,
                new AuditActionType("identity.credential.rotated", true),
                new AuditTargetType("service-credential"),
                replacementCredentialId.toString(),
                correlationId,
                new AuditReason(reason),
                new AuditDetails("{\"merchantId\":\"" + merchantId
                        + "\",\"previousCredentialId\":\"" + previousCredentialId
                        + "\",\"replacementCredentialId\":\""
                        + replacementCredentialId + "\"}"),
                clock
        ));
    }

    @Override
    public void appendCredentialRevoked(
            String actorIssuer,
            String actorSubject,
            UUID tenantId,
            UUID merchantId,
            UUID credentialId,
            String reason,
            String correlationId
    ) {
        append(AuditRecord.create(
                AuditRecordId.newId(),
                new AuditActorIdentity(actorIssuer, actorSubject),
                AuditPrincipalType.HUMAN,
                tenantId,
                new AuditActionType("identity.credential.revoked", true),
                new AuditTargetType("service-credential"),
                credentialId.toString(),
                correlationId,
                new AuditReason(reason),
                new AuditDetails("{\"merchantId\":\"" + merchantId + "\"}"),
                clock
        ));
    }

    private AuditRecord toDomain(AuditRecordJpaEntity entity) {
        return AuditRecord.create(
                new AuditRecordId(entity.id()),
                new AuditActorIdentity(entity.actorIssuer(), entity.actorSubject()),
                AuditPrincipalType.valueOf(entity.principalType()),
                entity.tenantId(),
                new AuditActionType(entity.actionType(), entity.tenantId() != null),
                new AuditTargetType(entity.targetType()),
                entity.targetId(),
                entity.correlationId(),
                new AuditReason(entity.reason()),
                new AuditDetails(entity.details()),
                Clock.fixed(entity.occurredAt(), java.time.ZoneOffset.UTC)
        );
    }

    private String escapeJson(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
