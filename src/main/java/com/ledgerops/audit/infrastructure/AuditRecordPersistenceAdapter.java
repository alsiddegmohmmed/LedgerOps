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
