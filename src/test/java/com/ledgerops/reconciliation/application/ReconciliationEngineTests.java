package com.ledgerops.reconciliation.application;

import com.ledgerops.ledger.api.LedgerSettlementEvidenceQuery;
import com.ledgerops.payment.api.PaymentReconciliationPage;
import com.ledgerops.payment.api.PaymentReconciliationQuery;
import com.ledgerops.payment.api.PaymentReconciliationSubject;
import com.ledgerops.payment.api.ReconciliationSubjectCursor;
import com.ledgerops.payment.api.ReconciliationSubjectType;
import com.ledgerops.provider.api.ProviderEvidenceBatchQuery;
import com.ledgerops.reconciliation.api.SettlementBatchSnapshot;
import com.ledgerops.reconciliation.domain.ReconciliationDiscrepancyCategory;
import com.ledgerops.reconciliation.domain.SettlementBatchStatus;
import com.ledgerops.reconciliation.domain.SettlementOperationType;
import com.ledgerops.reconciliation.domain.SettlementRecord;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReconciliationEngineTests {

    private static final UUID TENANT_ID = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
    private static final UUID FAMILY_ID = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
    private static final UUID BATCH_ID = UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc");
    private static final UUID SNAPSHOT_ID = UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddddddd");
    private static final UUID RUN_ID = UUID.fromString("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee");
    private static final UUID PAYMENT_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID REVERSAL_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final Instant NOW = Instant.parse("2026-08-11T08:00:00Z");
    private static final Currency USD = Currency.getInstance("USD");

    @Test
    void createsMissingProviderRecordForAnInternalSubjectWithoutSettlementOccurrence() {
        SettlementBatchStore batches = mock(SettlementBatchStore.class);
        PaymentReconciliationQuery payments = mock(PaymentReconciliationQuery.class);
        ProviderEvidenceBatchQuery provider = mock(ProviderEvidenceBatchQuery.class);
        LedgerSettlementEvidenceQuery ledger = mock(LedgerSettlementEvidenceQuery.class);
        ReconciliationSnapshotStore snapshots = mock(ReconciliationSnapshotStore.class);
        ReconciliationCaseCommandService cases = mock(ReconciliationCaseCommandService.class);

        PaymentReconciliationSubject payment = paymentSubject(PAYMENT_ID, "payment:" + PAYMENT_ID);
        ReconciliationSnapshotStore.ReconciliationSnapshotSubjectRow row = subjectRow(
                ReconciliationSubjectType.PAYMENT, PAYMENT_ID, PAYMENT_ID,
                "payment:" + PAYMENT_ID, "SUCCESS");
        arrangeCommon(batches, payments, provider, ledger, snapshots, payment);
        when(snapshots.readOccurrences(SNAPSHOT_ID, 0, 500)).thenReturn(List.of());
        when(snapshots.findSubjectsWithoutSettlementRecord(SNAPSHOT_ID, 0, 500))
                .thenReturn(List.of(row));
        when(snapshots.findSubjectsWithoutSettlementRecord(SNAPSHOT_ID, 1, 500))
                .thenReturn(List.of());

        ReconciliationEngine engine = engine(
                batches, payments, provider, ledger, snapshots, cases);
        engine.execute(command());

        var results = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(snapshots).insertResults(eq(RUN_ID), eq(TENANT_ID), results.capture(), any());
        @SuppressWarnings("unchecked")
        List<ReconciliationResultDraft> captured = (List<ReconciliationResultDraft>) results.getValue();
        assertThat(captured).singleElement().satisfies(result -> {
            assertThat(result.occurrenceId()).isNull();
            assertThat(result.discrepancyCategory())
                    .isEqualTo(ReconciliationDiscrepancyCategory.MISSING_PROVIDER_RECORD);
            assertThat(result.subjectId()).isEqualTo(PAYMENT_ID);
        });
    }

    @Test
    void classifiesReversalWithoutAnExactPaymentSettlementAsAReconciliationDiscrepancy() {
        SettlementBatchStore batches = mock(SettlementBatchStore.class);
        PaymentReconciliationQuery payments = mock(PaymentReconciliationQuery.class);
        ProviderEvidenceBatchQuery provider = mock(ProviderEvidenceBatchQuery.class);
        LedgerSettlementEvidenceQuery ledger = mock(LedgerSettlementEvidenceQuery.class);
        ReconciliationSnapshotStore snapshots = mock(ReconciliationSnapshotStore.class);
        ReconciliationCaseCommandService cases = mock(ReconciliationCaseCommandService.class);

        PaymentReconciliationSubject reversal = paymentSubject(
                REVERSAL_ID, "reversal:" + REVERSAL_ID, ReconciliationSubjectType.REVERSAL);
        ReconciliationSnapshotStore.SubjectKey key =
                new ReconciliationSnapshotStore.SubjectKey("REVERSAL", REVERSAL_ID);
        ReconciliationSnapshotStore.ReconciliationSnapshotSubjectRow row = subjectRow(
                ReconciliationSubjectType.REVERSAL, REVERSAL_ID, PAYMENT_ID,
                "reversal:" + REVERSAL_ID, "SUCCESS");
        SettlementRecord record = settlementRecord(
                SettlementOperationType.REVERSAL, REVERSAL_ID, "REVERSED", "10.00");
        ReconciliationSnapshotOccurrence occurrence = occurrence(record);

        arrangeCommon(batches, payments, provider, ledger, snapshots, reversal);
        when(snapshots.readOccurrences(SNAPSHOT_ID, 0, 500)).thenReturn(List.of(occurrence));
        when(snapshots.readOccurrences(SNAPSHOT_ID, 1, 500)).thenReturn(List.of());
        when(snapshots.findSubjects(SNAPSHOT_ID, List.of(key))).thenReturn(Map.of(key, row));
        when(snapshots.findSubjectKeysByProviderReferences(eq(SNAPSHOT_ID), anyCollection()))
                .thenReturn(Map.of());
        when(snapshots.findSubjectsWithoutSettlementRecord(SNAPSHOT_ID, 0, 500))
                .thenReturn(List.of());

        ReconciliationEngine engine = engine(
                batches, payments, provider, ledger, snapshots, cases);
        engine.execute(command());

        var results = org.mockito.ArgumentCaptor.forClass(List.class);
        verify(snapshots).insertResults(eq(RUN_ID), eq(TENANT_ID), results.capture(), any());
        @SuppressWarnings("unchecked")
        List<ReconciliationResultDraft> captured = (List<ReconciliationResultDraft>) results.getValue();
        assertThat(captured).singleElement().satisfies(result -> assertThat(result.discrepancyCategory())
                .isEqualTo(ReconciliationDiscrepancyCategory.REVERSAL_WITHOUT_PAYMENT_SETTLEMENT));
    }

    private void arrangeCommon(
            SettlementBatchStore batches,
            PaymentReconciliationQuery payments,
            ProviderEvidenceBatchQuery provider,
            LedgerSettlementEvidenceQuery ledger,
            ReconciliationSnapshotStore snapshots,
            PaymentReconciliationSubject subject
    ) {
        when(batches.findById(TENANT_ID, BATCH_ID)).thenReturn(Optional.of(
                new SettlementBatchSnapshot(BATCH_ID, FAMILY_ID, TENANT_ID, "SIMULATOR", "batch-1",
                        LocalDate.of(2026, 8, 11), LocalDate.of(2026, 8, 11), "hash", "object", 10,
                        SettlementBatchStatus.COMPLETED, null, 1, 1, 0, null, NOW, NOW)));
        when(snapshots.createBuildingSnapshot(
                eq(TENANT_ID), eq(FAMILY_ID), eq(BATCH_ID), eq("rules-v1"), any(), any()))
                .thenReturn(new ReconciliationSnapshotStore.SnapshotIdentity(
                        SNAPSHOT_ID, TENANT_ID, FAMILY_ID, BATCH_ID, 1, "rules-v1", NOW));
        when(snapshots.createQueuedRun(any(), any())).thenReturn(RUN_ID);
        when(payments.findPage(any())).thenReturn(new PaymentReconciliationPage(
                List.of(subject), (ReconciliationSubjectCursor) null));
        when(provider.findByTenantAndEvidenceIds(any(), anyCollection())).thenReturn(Map.of());
        when(ledger.findBySources(any(), anyCollection())).thenReturn(Map.of());
    }

    private ReconciliationEngine engine(
            SettlementBatchStore batches,
            PaymentReconciliationQuery payments,
            ProviderEvidenceBatchQuery provider,
            LedgerSettlementEvidenceQuery ledger,
            ReconciliationSnapshotStore snapshots,
            ReconciliationCaseCommandService cases
    ) {
        return new ReconciliationEngine(
                batches, payments, provider, ledger, snapshots, cases,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private ReconciliationEngine.ExecuteCommand command() {
        return new ReconciliationEngine.ExecuteCommand(
                TENANT_ID, BATCH_ID, "rules-v1", NOW);
    }

    private PaymentReconciliationSubject paymentSubject(UUID subjectId, String idempotencyKey) {
        return paymentSubject(subjectId, idempotencyKey, ReconciliationSubjectType.PAYMENT);
    }

    private PaymentReconciliationSubject paymentSubject(
            UUID subjectId, String idempotencyKey, ReconciliationSubjectType type
    ) {
        return new PaymentReconciliationSubject(
                TENANT_ID, type, subjectId, PAYMENT_ID, UUID.fromString("33333333-3333-4333-8333-333333333333"),
                new BigDecimal("10.00"), USD, "SIMULATOR", idempotencyKey,
                UUID.fromString("44444444-4444-4444-8444-444444444444"),
                UUID.fromString("55555555-5555-4555-8555-555555555555"),
                "provider-ref-1", "COMPLETED", NOW);
    }

    private ReconciliationSnapshotStore.ReconciliationSnapshotSubjectRow subjectRow(
            ReconciliationSubjectType type,
            UUID subjectId,
            UUID paymentId,
            String idempotencyKey,
            String providerCategory
    ) {
        String debitAccount = type == ReconciliationSubjectType.PAYMENT
                ? "PROVIDER_CLEARING" : "MERCHANT_PAYABLE";
        String creditAccount = type == ReconciliationSubjectType.PAYMENT
                ? "MERCHANT_PAYABLE" : "PROVIDER_CLEARING";
        String ledgerEntries = "[{\"accountCode\":\"" + debitAccount
                + "\",\"direction\":\"DEBIT\",\"amount\":\"10.00\",\"currency\":\"USD\"},"
                + "{\"accountCode\":\"" + creditAccount
                + "\",\"direction\":\"CREDIT\",\"amount\":\"10.00\",\"currency\":\"USD\"}]";
        return new ReconciliationSnapshotStore.ReconciliationSnapshotSubjectRow(
                new ReconciliationSnapshotStore.SubjectKey(type.name(), subjectId), paymentId,
                UUID.fromString("33333333-3333-4333-8333-333333333333"), new BigDecimal("10.00"), USD,
                "SIMULATOR", idempotencyKey,
                UUID.fromString("44444444-4444-4444-8444-444444444444"),
                UUID.fromString("55555555-5555-4555-8555-555555555555"), "provider-ref-1",
                providerCategory, NOW, "COMPLETED", NOW,
                UUID.fromString("66666666-6666-4666-8666-666666666666"), NOW,
                null, new BigDecimal("10.00"), new BigDecimal("10.00"), ledgerEntries);
    }

    private SettlementRecord settlementRecord(
            SettlementOperationType operationType, UUID subjectId, String status, String amount
    ) {
        String prefix = operationType == SettlementOperationType.PAYMENT ? "payment:" : "reversal:";
        return SettlementRecord.fromFields(List.of(
                "batch-1", "2026-08-11", "2026-08-11", "record-1", operationType.name(),
                prefix + subjectId, "provider-ref-1", amount, "USD", status,
                "2026-08-11", "2026-08-11T01:00:00Z"));
    }

    private ReconciliationSnapshotOccurrence occurrence(SettlementRecord record) {
        return new ReconciliationSnapshotOccurrence(
                SNAPSHOT_ID, TENANT_ID, BATCH_ID, UUID.fromString("77777777-7777-4777-8777-777777777777"),
                UUID.fromString("88888888-8888-4888-8888-888888888888"), 1,
                record.providerRecordKey(), record.normalizedContentHash(),
                record.normalizedContentJson(), "VALID", null);
    }
}
