package com.ledgerops.audit.infrastructure;

import com.ledgerops.audit.domain.AuditActionType;
import com.ledgerops.audit.domain.AuditActorIdentity;
import com.ledgerops.audit.domain.AuditDetails;
import com.ledgerops.audit.domain.AuditPrincipalType;
import com.ledgerops.audit.domain.AuditReason;
import com.ledgerops.audit.domain.AuditRecord;
import com.ledgerops.audit.domain.AuditRecordId;
import com.ledgerops.audit.domain.AuditRecordRepository;
import com.ledgerops.audit.domain.AuditTargetType;
import com.ledgerops.support.PostgresTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
class AuditRecordPersistenceIntegrationTests {

    @Autowired
    private AuditRecordRepository auditRecordRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void appendsAndLoadsEvidenceThroughThePersistenceBoundary() {
        AuditRecord record = record();

        AuditRecord persisted = auditRecordRepository.append(record);

        assertEquals(record.id(), persisted.id());
        assertEquals(record.actorIdentity(), persisted.actorIdentity());
        assertEquals(record.principalType(), persisted.principalType());
        assertEquals(record.tenantId(), persisted.tenantId());
        assertEquals(record.actionType(), persisted.actionType());
        assertEquals(record.targetId(), persisted.targetId());
        assertEquals(record.correlationId(), persisted.correlationId());
        assertEquals(record.occurredAt(), persisted.occurredAt());
    }

    @Test
    void rejectsUpdateAndDeleteAtTheDatabaseBoundary() {
        AuditRecord record = record();
        auditRecordRepository.append(record);

        assertThrows(DataAccessException.class, () -> jdbcTemplate.update(
                "UPDATE audit.audit_records SET reason = ? WHERE id = ?",
                "changed",
                record.id().value()
        ));
        assertThrows(DataAccessException.class, () -> jdbcTemplate.update(
                "DELETE FROM audit.audit_records WHERE id = ?",
                record.id().value()
        ));
    }

    private AuditRecord record() {
        UUID tenantId = UUID.randomUUID();
        return AuditRecord.create(
                AuditRecordId.newId(),
                new AuditActorIdentity("issuer", "subject"),
                AuditPrincipalType.HUMAN,
                tenantId,
                new AuditActionType("tenant.updated", true),
                new AuditTargetType("tenant"),
                tenantId.toString(),
                "correlation-" + UUID.randomUUID(),
                new AuditReason("configuration change"),
                AuditDetails.empty(),
                Clock.fixed(Instant.parse("2026-07-28T10:15:30Z"), ZoneOffset.UTC)
        );
    }
}
