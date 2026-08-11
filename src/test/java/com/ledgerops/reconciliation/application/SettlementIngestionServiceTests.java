package com.ledgerops.reconciliation.application;

import com.ledgerops.audit.api.AuditAppendPort;
import com.ledgerops.identity.api.AuthenticatedPrincipal;
import com.ledgerops.identity.api.AuthorizedRequestContext;
import com.ledgerops.identity.domain.Permission;
import com.ledgerops.identity.domain.PrincipalType;
import com.ledgerops.identity.domain.ScopeMode;
import com.ledgerops.messaging.api.MessageOutbox;
import com.ledgerops.reconciliation.api.SettlementBatchSnapshot;
import com.ledgerops.reconciliation.domain.SettlementBatchStatus;
import com.ledgerops.reconciliation.domain.SettlementValidationReasonCode;
import org.springframework.batch.infrastructure.support.transaction.ResourcelessTransactionManager;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SettlementIngestionServiceTests {

    private static final UUID TENANT_ID = UUID.fromString("00000000-0000-0000-0000-000000000010");
    private static final UUID BATCH_ID = UUID.fromString("00000000-0000-0000-0000-000000000011");
    private static final Instant NOW = Instant.parse("2026-08-11T00:00:00Z");
    private static final String CORRELATION_ID = "00000000-0000-0000-0000-000000000012";

    @Test
    void inconsistentBatchIdentityFailsTheFileInsteadOfImportingRows() {
        SettlementBatchStore store = mock(SettlementBatchStore.class);
        ObjectStoragePort objectStorage = mock(ObjectStoragePort.class);
        SettlementBatchJobLauncher jobLauncher = mock(SettlementBatchJobLauncher.class);
        AuditAppendPort audit = mock(AuditAppendPort.class);
        MessageOutbox outbox = mock(MessageOutbox.class);
        SettlementBatchSnapshot snapshot = snapshot();
        when(store.findById(TENANT_ID, BATCH_ID)).thenReturn(Optional.of(snapshot));
        when(objectStorage.open("settlements/test.csv")).thenReturn(new ByteArrayInputStream(csv().getBytes(
                StandardCharsets.UTF_8)));

        SettlementIngestionService service = new SettlementIngestionService(
                store,
                objectStorage,
                new SettlementCsvParser(),
                jobLauncher,
                audit,
                outbox,
                Clock.fixed(NOW, java.time.ZoneOffset.UTC),
                new ResourcelessTransactionManager());

        service.validate(TENANT_ID, BATCH_ID, context(),
                new AuthenticatedPrincipal("HUMAN", "issuer", "subject"));

        verify(store).failValidation(
                TENANT_ID, BATCH_ID, SettlementValidationReasonCode.INCONSISTENT_BATCH_IDENTITY, NOW);
        verify(store, never()).finishValidation(
                org.mockito.ArgumentMatchers.eq(TENANT_ID),
                org.mockito.ArgumentMatchers.eq(BATCH_ID),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any());
        verify(store, never()).persistValidationChunk(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.anyList());
    }

    private SettlementBatchSnapshot snapshot() {
        return new SettlementBatchSnapshot(
                BATCH_ID,
                UUID.fromString("00000000-0000-0000-0000-000000000013"),
                TENANT_ID,
                "SIMULATOR",
                "batch-1",
                LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 31),
                "a".repeat(64),
                "settlements/test.csv",
                256,
                SettlementBatchStatus.RECEIVED,
                null,
                0,
                0,
                0,
                null,
                NOW,
                NOW);
    }

    private AuthorizedRequestContext context() {
        return new AuthorizedRequestContext(
                PrincipalType.HUMAN,
                UUID.fromString("00000000-0000-0000-0000-000000000014"),
                null,
                TENANT_ID,
                ScopeMode.TENANT_WIDE,
                Set.of(),
                Set.of(Permission.RECONCILIATION_READ),
                CORRELATION_ID);
    }

    private String csv() {
        return String.join("\n",
                String.join(",", SettlementCsvParser.HEADER),
                "batch-1,2026-08-01,2026-08-31,record-1,PAYMENT,payment:00000000-0000-0000-0000-000000000001,provider-1,10.00,SAR,SUCCESS,2026-08-01,2026-08-01T12:00:00Z",
                "batch-2,2026-08-01,2026-08-31,record-2,PAYMENT,payment:00000000-0000-0000-0000-000000000002,provider-2,11.00,SAR,SUCCESS,2026-08-01,2026-08-01T12:00:00Z",
                "");
    }
}
