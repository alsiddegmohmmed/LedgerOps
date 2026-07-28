package com.ledgerops.audit.domain;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class AuditRecordTests {

    private static final Instant OCCURRED_AT = Instant.parse("2026-07-28T10:15:30Z");

    @Test
    void createsImmutableTenantOwnedRecordWithClockTime() {
        UUID tenantId = UUID.randomUUID();

        AuditRecord record = AuditRecord.create(
                AuditRecordId.newId(),
                new AuditActorIdentity("issuer", "subject"),
                AuditPrincipalType.HUMAN,
                tenantId,
                new AuditActionType("tenant.updated", true),
                new AuditTargetType("tenant"),
                tenantId.toString(),
                "correlation-1",
                new AuditReason("configuration change"),
                new AuditDetails("{\"field\":\"name\"}"),
                Clock.fixed(OCCURRED_AT, ZoneOffset.UTC)
        );

        assertThat(record.tenantId()).isEqualTo(tenantId);
        assertThat(record.occurredAt()).isEqualTo(OCCURRED_AT);
        assertThat(record.actionType().tenantOwned()).isTrue();
    }

    @Test
    void rejectsTenantOwnedRecordWithoutValidatedTenantContext() {
        assertThatIllegalArgumentException().isThrownBy(() -> AuditRecord.create(
                AuditRecordId.newId(),
                new AuditActorIdentity("issuer", "subject"),
                AuditPrincipalType.HUMAN,
                null,
                new AuditActionType("tenant.updated", true),
                new AuditTargetType("tenant"),
                "tenant-1",
                "correlation-1",
                new AuditReason("configuration change"),
                AuditDetails.empty(),
                Clock.fixed(OCCURRED_AT, ZoneOffset.UTC)
        ));
    }

    @Test
    void rejectsSensitiveReasonAndDetailsContent() {
        assertThatIllegalArgumentException().isThrownBy(() -> new AuditReason("password=secret"));
        assertThatIllegalArgumentException().isThrownBy(() -> new AuditDetails("{\"access_token\":\"value\"}"));
    }
}
