package com.ledgerops.reconciliation.application;

import com.ledgerops.audit.api.AuditAppendPort;
import com.ledgerops.identity.api.AuthenticatedPrincipal;
import com.ledgerops.identity.api.AuthorizedRequestContext;
import com.ledgerops.identity.domain.Permission;
import com.ledgerops.identity.domain.PrincipalType;
import com.ledgerops.identity.domain.ScopeMode;
import com.ledgerops.ledger.api.LedgerSettlementEvidenceQuery;
import com.ledgerops.messaging.api.MessageOutbox;
import com.ledgerops.payment.api.PaymentReconciliationPage;
import com.ledgerops.payment.api.PaymentReconciliationQuery;
import com.ledgerops.provider.api.ProviderEvidenceBatchQuery;
import com.ledgerops.reconciliation.api.SettlementBatchSnapshot;
import com.ledgerops.reconciliation.domain.SettlementBatchStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.springframework.batch.infrastructure.support.transaction.ResourcelessTransactionManager;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Release-gate evidence for the documented 100,000-record settlement bound.
 *
 * These tests use in-memory ports deliberately: they prove the application
 * chunk/page behavior without creating a second Docker database or polluting
 * the local demo database.
 */
@Tag("release-gate-scale")
class Release03ScaleEvidenceTests {

    private static final int RECORD_COUNT = 100_000;
    private static final int PAGE_SIZE = 500;
    private static final int PAGE_COUNT = RECORD_COUNT / PAGE_SIZE;
    private static final UUID TENANT_ID = UUID.fromString(
            "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private static final UUID FAMILY_ID = UUID.fromString(
            "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
    private static final UUID BATCH_ID = UUID.fromString(
            "cccccccc-cccc-4ccc-8ccc-cccccccccccc");
    private static final UUID SNAPSHOT_ID = UUID.fromString(
            "dddddddd-dddd-4ddd-8ddd-dddddddddddd");
    private static final UUID RUN_ID = UUID.fromString(
            "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee");
    private static final Instant NOW = Instant.parse("2026-08-11T08:00:00Z");

    @Test
    void validatesOneHundredThousandRowsInFiveHundredRowChunks() {
        SettlementBatchStore store = mock(SettlementBatchStore.class);
        ObjectStoragePort objectStorage = mock(ObjectStoragePort.class);
        SettlementBatchJobLauncher jobLauncher = mock(SettlementBatchJobLauncher.class);
        AuditAppendPort audit = mock(AuditAppendPort.class);
        MessageOutbox outbox = mock(MessageOutbox.class);
        when(store.findById(TENANT_ID, BATCH_ID)).thenReturn(java.util.Optional.of(receivedBatch()));
        when(objectStorage.open("scale.csv")).thenReturn(
                new ByteArrayInputStream(scaleCsv().getBytes(StandardCharsets.UTF_8)));

        List<Integer> chunkSizes = new ArrayList<>();
        doAnswer(invocation -> {
            chunkSizes.add(((List<?>) invocation.getArgument(2)).size());
            return null;
        }).when(store).persistValidationChunk(any(), any(), anyList(), anyList());

        SettlementIngestionService service = new SettlementIngestionService(
                store,
                objectStorage,
                new SettlementCsvParser(),
                jobLauncher,
                audit,
                outbox,
                Clock.fixed(NOW, ZoneOffset.UTC),
                new ResourcelessTransactionManager());

        service.validate(TENANT_ID, BATCH_ID, context(),
                new AuthenticatedPrincipal("HUMAN", "issuer", "subject"));

        verify(store).finishValidation(
                eq(TENANT_ID), eq(BATCH_ID), eq((long) RECORD_COUNT),
                eq((long) RECORD_COUNT), eq(0L), eq(NOW));
        assertThat(chunkSizes).hasSize(PAGE_COUNT).containsOnly(PAGE_SIZE);
    }

    @Test
    void reconcilesOneHundredThousandOccurrencesThroughFiveHundredRowPages() {
        SettlementBatchStore batches = mock(SettlementBatchStore.class);
        PaymentReconciliationQuery payments = mock(PaymentReconciliationQuery.class);
        ProviderEvidenceBatchQuery provider = mock(ProviderEvidenceBatchQuery.class);
        LedgerSettlementEvidenceQuery ledger = mock(LedgerSettlementEvidenceQuery.class);
        ReconciliationSnapshotStore snapshots = mock(ReconciliationSnapshotStore.class);
        ReconciliationCaseCommandService cases = mock(ReconciliationCaseCommandService.class);

        when(batches.findById(TENANT_ID, BATCH_ID)).thenReturn(java.util.Optional.of(completedBatch()));
        when(snapshots.createBuildingSnapshot(
                eq(TENANT_ID), eq(FAMILY_ID), eq(BATCH_ID), eq("rules-v1"), any(), any()))
                .thenReturn(new ReconciliationSnapshotStore.SnapshotIdentity(
                        SNAPSHOT_ID, TENANT_ID, FAMILY_ID, BATCH_ID, 1, "rules-v1", NOW));
        when(snapshots.createQueuedRun(any(), any())).thenReturn(RUN_ID);
        when(payments.findPage(any())).thenReturn(new PaymentReconciliationPage(List.of(), null));
        when(provider.findByTenantAndEvidenceIds(any(), anyCollection())).thenReturn(Map.of());
        when(ledger.findBySources(any(), anyCollection())).thenReturn(Map.of());
        when(snapshots.findSubjects(any(), anyCollection())).thenReturn(Map.of());
        when(snapshots.findSubjectKeysByProviderReferences(any(), anyCollection()))
                .thenReturn(Map.of());
        when(snapshots.findSubjectsWithoutSettlementRecord(any(), anyInt(), eq(PAGE_SIZE)))
                .thenReturn(List.of());
        when(batches.readOccurrences(eq(BATCH_ID), anyInt(), eq(PAGE_SIZE)))
                .thenAnswer(invocation -> settlementPage(invocation.getArgument(1)));

        AtomicInteger snapshotReads = new AtomicInteger();
        when(snapshots.readOccurrences(eq(SNAPSHOT_ID), anyInt(), eq(PAGE_SIZE)))
                .thenAnswer(invocation -> {
                    snapshotReads.incrementAndGet();
                    return snapshotPage(invocation.getArgument(1));
                });

        ReconciliationEngine engine = new ReconciliationEngine(
                batches, payments, provider, ledger, snapshots, cases,
                Clock.fixed(NOW, ZoneOffset.UTC));

        ReconciliationRunExecution execution = engine.execute(
                new ReconciliationEngine.ExecuteCommand(TENANT_ID, BATCH_ID, "rules-v1", NOW));

        assertThat(execution.status().name()).isEqualTo("COMPLETED_WITH_DISCREPANCIES");
        assertThat(execution.counts().matchedCount()).isZero();
        assertThat(execution.counts().unmatchedCount()).isZero();
        assertThat(execution.counts().discrepancyCount()).isEqualTo(RECORD_COUNT);
        assertThat(snapshotReads).hasValue(PAGE_COUNT * 2 + 2);
        verify(snapshots, org.mockito.Mockito.times(PAGE_COUNT))
                .insertResults(eq(RUN_ID), eq(TENANT_ID), anyList(), eq(NOW));
    }

    private AuthorizedRequestContext context() {
        return new AuthorizedRequestContext(
                PrincipalType.HUMAN,
                UUID.fromString("11111111-1111-4111-8111-111111111111"),
                null,
                TENANT_ID,
                ScopeMode.TENANT_WIDE,
                Set.of(),
                Set.of(Permission.RECONCILIATION_READ),
                "22222222-2222-4222-8222-222222222222");
    }

    private SettlementBatchSnapshot receivedBatch() {
        return new SettlementBatchSnapshot(
                BATCH_ID, FAMILY_ID, TENANT_ID, "SIMULATOR", "batch-scale",
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31),
                "a".repeat(64), "scale.csv", 1,
                SettlementBatchStatus.RECEIVED, null, 0, 0, 0, null, NOW, NOW);
    }

    private SettlementBatchSnapshot completedBatch() {
        return new SettlementBatchSnapshot(
                BATCH_ID, FAMILY_ID, TENANT_ID, "SIMULATOR", "batch-scale",
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31),
                "a".repeat(64), "scale.csv", 1,
                SettlementBatchStatus.COMPLETED, null, RECORD_COUNT, RECORD_COUNT, 0,
                null, NOW, NOW);
    }

    private String scaleCsv() {
        StringBuilder csv = new StringBuilder(18_000_000)
                .append(String.join(",", SettlementCsvParser.HEADER)).append('\n');
        for (int index = 1; index <= RECORD_COUNT; index++) {
            String subjectId = String.format(java.util.Locale.ROOT,
                    "00000000-0000-4000-8000-%012d", index);
            csv.append("batch-scale,2026-08-01,2026-08-31,record-")
                    .append(index)
                    .append(",PAYMENT,payment:")
                    .append(subjectId)
                    .append(",provider-")
                    .append(index)
                    .append(",10.00,USD,SUCCESS,2026-08-01,2026-08-01T12:00:00Z\n");
        }
        return csv.toString();
    }

    private List<SettlementBatchStore.SettlementOccurrenceRow> settlementPage(int page) {
        if (page >= PAGE_COUNT) {
            return List.of();
        }
        List<SettlementBatchStore.SettlementOccurrenceRow> rows = new ArrayList<>(PAGE_SIZE);
        for (int offset = 0; offset < PAGE_SIZE; offset++) {
            int index = page * PAGE_SIZE + offset + 1;
            rows.add(new SettlementBatchStore.SettlementOccurrenceRow(
                    id("occurrence-" + index), BATCH_ID, TENANT_ID, index,
                    "record-" + index, null, "{}", null,
                    "QUARANTINED", "INVALID_FIELD"));
        }
        return rows;
    }

    private List<ReconciliationSnapshotOccurrence> snapshotPage(int page) {
        if (page >= PAGE_COUNT) {
            return List.of();
        }
        List<ReconciliationSnapshotOccurrence> rows = new ArrayList<>(PAGE_SIZE);
        for (int offset = 0; offset < PAGE_SIZE; offset++) {
            int index = page * PAGE_SIZE + offset + 1;
            rows.add(new ReconciliationSnapshotOccurrence(
                    SNAPSHOT_ID, TENANT_ID, BATCH_ID, id("snapshot-occurrence-" + index),
                    null, index, "record-" + index, null, "{}",
                    "QUARANTINED", "INVALID_FIELD"));
        }
        return rows;
    }

    private UUID id(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }
}
