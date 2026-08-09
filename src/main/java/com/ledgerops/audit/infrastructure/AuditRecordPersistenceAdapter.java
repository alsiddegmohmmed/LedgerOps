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
                new AuditReason("Merchant lifecycle change"),
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
                new AuditReason("Tenant configuration change"),
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
                new AuditReason("Operational contact change"),
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
}
