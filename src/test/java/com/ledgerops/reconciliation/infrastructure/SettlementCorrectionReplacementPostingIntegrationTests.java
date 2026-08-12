package com.ledgerops.reconciliation.infrastructure;

import com.ledgerops.casework.api.CorrectionRequestCommand;
import com.ledgerops.casework.api.CorrectionRequestSnapshot;
import com.ledgerops.casework.application.CorrectionApplicationService;
import com.ledgerops.casework.domain.CorrectionRequestStatus;
import com.ledgerops.reconciliation.application.ReconciliationSnapshotStore;
import com.ledgerops.reconciliation.application.ReconciliationSettlementPostingService;
import com.ledgerops.support.PostgresTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
class SettlementCorrectionReplacementPostingIntegrationTests {

    private static final Instant NOW = Instant.parse("2026-08-11T09:00:00Z");

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private CorrectionApplicationService corrections;

    @Autowired
    private ReconciliationSnapshotStore snapshots;

    @Autowired
    private ReconciliationSettlementPostingService postings;

    @Test
    void correctedFileStaysBlockedUntilCorrectionThenPostsReplacementExactlyOnce() {
        Fixture fixture = seed();

        assertThat(jdbc.queryForObject("""
                SELECT count(*)
                  FROM reconciliation.settlement_posting_applications a
                  JOIN reconciliation.settlement_posting_instructions i
                    ON i.settlement_posting_id = a.settlement_posting_id
                 WHERE a.tenant_id = ? AND a.status = 'POSTED' AND i.run_id = ?
                """, Integer.class, fixture.tenantId(),
                jdbc.queryForObject("""
                        SELECT run_id
                          FROM reconciliation.current_reconciliation_runs
                         WHERE tenant_id = ? AND batch_family_id = ?
                        """, UUID.class, fixture.tenantId(), fixture.batchFamilyId())))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*)
                  FROM reconciliation.settlement_posting_applications a
                  JOIN reconciliation.settlement_posting_instructions i
                    ON i.settlement_posting_id = a.settlement_posting_id
                 WHERE a.tenant_id = ? AND a.status = 'POSTED' AND i.run_id = ?
                   AND NOT EXISTS (
                       SELECT 1
                         FROM reconciliation.reconciliation_results r
                        WHERE r.run_id = ? AND r.result_status = 'MATCHED'
                          AND r.canonical_record_version_id = i.canonical_record_version_id
                          AND r.subject_type = i.subject_type AND r.subject_id = i.subject_id)
                """, Integer.class, fixture.tenantId(),
                jdbc.queryForObject("""
                        SELECT run_id
                          FROM reconciliation.current_reconciliation_runs
                         WHERE tenant_id = ? AND batch_family_id = ?
                        """, UUID.class, fixture.tenantId(), fixture.batchFamilyId()),
                fixture.correctedRunId())).isEqualTo(1);

        assertThatThrownBy(() -> snapshots.promoteCurrentRun(
                fixture.tenantId(), fixture.batchFamilyId(), fixture.correctedRunId(), NOW))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("uncompensated settlement posting");

        CorrectionRequestSnapshot correction = corrections.request(new CorrectionRequestCommand(
                fixture.tenantId(), fixture.caseId(), fixture.discrepancyId(),
                fixture.originalSettlementPostingId(), fixture.originalLedgerTransactionId(),
                fixture.actorId(), "Compensate the invalidated settlement adjustment",
                fixture.correlationId(), true));

        assertThat(correction.status()).isEqualTo(CorrectionRequestStatus.COMPLETED);
        assertThat(correction.compensationLedgerTransactionId()).isNotNull();

        snapshots.promoteCurrentRun(
                fixture.tenantId(), fixture.batchFamilyId(), fixture.correctedRunId(), NOW.plusSeconds(1));

        var prepared = postings.prepareCurrentRun(new ReconciliationSettlementPostingService.PrepareCommand(
                fixture.tenantId(), fixture.batchFamilyId(), fixture.correctedRunId()));
        assertThat(prepared).hasSize(1);
        assertThat(prepared.getFirst().applicationStatus()).isEqualTo("PENDING");

        var firstPost = postings.postCurrent(new ReconciliationSettlementPostingService.PostCommand(
                fixture.tenantId(), fixture.batchFamilyId(), fixture.correctedRunId()));
        assertThat(firstPost).singleElement().satisfies(outcome -> {
            assertThat(outcome.status()).isEqualTo("POSTED");
            assertThat(outcome.ledgerTransactionId()).isNotNull();
        });

        var replay = postings.postCurrent(new ReconciliationSettlementPostingService.PostCommand(
                fixture.tenantId(), fixture.batchFamilyId(), fixture.correctedRunId()));
        assertThat(replay).singleElement().satisfies(outcome -> {
            assertThat(outcome.status()).isEqualTo("REPLAYED");
            assertThat(outcome.ledgerTransactionId()).isNotNull();
        });

        assertThat(jdbc.queryForObject("""
                SELECT run_id
                  FROM reconciliation.current_reconciliation_runs
                 WHERE tenant_id = ? AND batch_family_id = ?
                """, UUID.class, fixture.tenantId(), fixture.batchFamilyId()))
                .isEqualTo(fixture.correctedRunId());
        assertThat(jdbc.queryForObject("""
                SELECT count(*)
                  FROM reconciliation.settlement_posting_applications
                 WHERE tenant_id = ? AND status = 'POSTED'
                """, Integer.class, fixture.tenantId())).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT count(*)
                  FROM ledger.transactions
                 WHERE tenant_id = ? AND source_type = 'SETTLEMENT_ADJUSTMENT'
                """, Integer.class, fixture.tenantId())).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT count(*)
                  FROM ledger.transactions
                 WHERE tenant_id = ? AND source_type = 'AUTHORISED_CORRECTION'
                """, Integer.class, fixture.tenantId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*)
                  FROM reconciliation.settlement_posting_instructions
                 WHERE tenant_id = ? AND subject_id = ?
                """, Integer.class, fixture.tenantId(), fixture.subjectId())).isEqualTo(2);
    }

    @Test
    void concurrentDuplicateCorrectionRequestsCreateOneDurableCompensation() throws Exception {
        Fixture fixture = seed();
        CorrectionRequestCommand command = new CorrectionRequestCommand(
                fixture.tenantId(), fixture.caseId(), fixture.discrepancyId(),
                fixture.originalSettlementPostingId(), fixture.originalLedgerTransactionId(),
                fixture.actorId(), "Compensate the invalidated settlement adjustment",
                fixture.correlationId(), true);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<CorrectionRequestSnapshot> first = executor.submit(
                    () -> corrections.request(command));
            Future<CorrectionRequestSnapshot> second = executor.submit(
                    () -> corrections.request(command));

            CorrectionRequestSnapshot firstResult = first.get(60, TimeUnit.SECONDS);
            CorrectionRequestSnapshot secondResult = second.get(60, TimeUnit.SECONDS);

            assertThat(firstResult.status()).isEqualTo(CorrectionRequestStatus.COMPLETED);
            assertThat(secondResult.status()).isEqualTo(CorrectionRequestStatus.COMPLETED);
            assertThat(secondResult.correctionId()).isEqualTo(firstResult.correctionId());
            assertThat(secondResult.compensationLedgerTransactionId())
                    .isEqualTo(firstResult.compensationLedgerTransactionId());
        } finally {
            executor.shutdownNow();
        }

        assertThat(jdbc.queryForObject("""
                SELECT count(*)
                  FROM casework.correction_requests
                 WHERE tenant_id = ? AND original_ledger_transaction_id = ?
                """, Integer.class, fixture.tenantId(), fixture.originalLedgerTransactionId()))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*)
                  FROM ledger.transactions
                 WHERE tenant_id = ? AND source_type = 'AUTHORISED_CORRECTION'
                """, Integer.class, fixture.tenantId())).isEqualTo(1);
    }

    @Test
    void promotionAndCorrectionSerializeWithoutDeadlock() throws Exception {
        Fixture fixture = seed();
        CorrectionRequestCommand command = new CorrectionRequestCommand(
                fixture.tenantId(), fixture.caseId(), fixture.discrepancyId(),
                fixture.originalSettlementPostingId(), fixture.originalLedgerTransactionId(),
                fixture.actorId(), "Compensate the invalidated settlement adjustment",
                fixture.correlationId(), true);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<CorrectionRequestSnapshot> correction = executor.submit(() -> {
                start.await();
                return corrections.request(command);
            });
            Future<Boolean> promotion = executor.submit(() -> {
                start.await();
                try {
                    snapshots.promoteCurrentRun(
                            fixture.tenantId(), fixture.batchFamilyId(), fixture.correctedRunId(), NOW);
                    return true;
                } catch (RuntimeException expectedWhileCorrectionIsUncommitted) {
                    return false;
                }
            });
            start.countDown();

            CorrectionRequestSnapshot result = correction.get(60, TimeUnit.SECONDS);
            boolean promoted = promotion.get(60, TimeUnit.SECONDS);

            assertThat(result.status()).isEqualTo(CorrectionRequestStatus.COMPLETED);
            assertThat(result.compensationLedgerTransactionId()).isNotNull();
            if (!promoted) {
                snapshots.promoteCurrentRun(
                        fixture.tenantId(), fixture.batchFamilyId(), fixture.correctedRunId(), NOW);
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(jdbc.queryForObject("""
                SELECT run_id
                  FROM reconciliation.current_reconciliation_runs
                 WHERE tenant_id = ? AND batch_family_id = ?
                """, UUID.class, fixture.tenantId(), fixture.batchFamilyId()))
                .isEqualTo(fixture.correctedRunId());
        assertThat(jdbc.queryForObject("""
                SELECT count(*)
                  FROM ledger.transactions
                 WHERE tenant_id = ? AND source_type = 'AUTHORISED_CORRECTION'
                """, Integer.class, fixture.tenantId())).isEqualTo(1);
    }

    private Fixture seed() {
        UUID tenantId = UUID.randomUUID();
        UUID familyId = UUID.randomUUID();
        UUID originalBatchVersionId = UUID.randomUUID();
        UUID correctedBatchVersionId = UUID.randomUUID();
        UUID originalSnapshotId = UUID.randomUUID();
        UUID invalidatingSnapshotId = UUID.randomUUID();
        UUID correctedSnapshotId = UUID.randomUUID();
        UUID originalRunId = UUID.randomUUID();
        UUID invalidatingRunId = UUID.randomUUID();
        UUID correctedRunId = UUID.randomUUID();
        UUID subjectId = UUID.randomUUID();
        UUID merchantId = UUID.randomUUID();
        UUID oldCanonicalId = UUID.randomUUID();
        UUID correctedCanonicalId = UUID.randomUUID();
        UUID originalOccurrenceId = UUID.randomUUID();
        UUID correctedOccurrenceId = UUID.randomUUID();
        UUID discrepancyId = UUID.randomUUID();
        UUID originalSettlementPostingId = UUID.randomUUID();
        UUID originalLedgerTransactionId = UUID.randomUUID();
        UUID caseId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();

        insertBatchFamily(tenantId, familyId);
        insertBatchVersion(originalBatchVersionId, familyId, "a".repeat(64), null);
        insertBatchVersion(correctedBatchVersionId, familyId, "b".repeat(64), originalBatchVersionId);
        jdbc.update("""
                INSERT INTO reconciliation.batch_family_controls
                    (batch_family_id, tenant_id, created_at)
                VALUES (?, ?, ?)
                """, familyId, tenantId, timestamp(NOW));

        insertSnapshot(originalSnapshotId, tenantId, familyId, originalBatchVersionId, 1, "c");
        insertSnapshot(invalidatingSnapshotId, tenantId, familyId, originalBatchVersionId, 2, "d");
        insertBuildingSnapshot(correctedSnapshotId, tenantId, familyId, correctedBatchVersionId, 3);
        insertRun(originalRunId, tenantId, familyId, originalBatchVersionId, originalSnapshotId,
                1, "COMPLETED", 0);
        insertRun(invalidatingRunId, tenantId, familyId, originalBatchVersionId, invalidatingSnapshotId,
                2, "COMPLETED_WITH_DISCREPANCIES", 1);
        jdbc.update("""
                INSERT INTO reconciliation.current_reconciliation_runs
                    (tenant_id, batch_family_id, run_id, promoted_at, updated_at)
                VALUES (?, ?, ?, ?, ?)
                """, tenantId, familyId, originalRunId, timestamp(NOW), timestamp(NOW));

        insertCanonical(oldCanonicalId, tenantId, "settlement-key", "f");
        insertCanonical(correctedCanonicalId, tenantId, "settlement-key", "1");
        insertOccurrence(originalBatchVersionId, originalOccurrenceId, tenantId, oldCanonicalId, "f");
        insertOccurrence(correctedBatchVersionId, correctedOccurrenceId, tenantId, correctedCanonicalId, "1");
        insertSnapshotOccurrence(correctedSnapshotId, correctedBatchVersionId, correctedOccurrenceId,
                correctedCanonicalId, tenantId);
        insertSnapshotSubject(correctedSnapshotId, correctedBatchVersionId, tenantId, subjectId, merchantId);
        jdbc.update("""
                UPDATE reconciliation.reconciliation_snapshots
                   SET snapshot_status = 'COMPLETE', snapshot_sha256 = ?,
                       captured_record_count = 1, captured_fact_count = 1,
                       completed_at = ?
                 WHERE snapshot_id = ?
                """, "e".repeat(64), timestamp(NOW), correctedSnapshotId);
        insertRun(correctedRunId, tenantId, familyId, correctedBatchVersionId, correctedSnapshotId,
                3, "COMPLETED", 0);

        jdbc.update("""
                INSERT INTO reconciliation.reconciliation_results
                    (result_id, tenant_id, run_id, snapshot_id, occurrence_id,
                     canonical_record_version_id, subject_type, subject_id,
                     result_status, discrepancy_category, provider_values,
                     internal_values, created_at)
                VALUES (?, ?, ?, ?, NULL, ?, 'PAYMENT', ?, 'DISCREPANCY',
                        'AMOUNT_MISMATCH', '{}'::jsonb, '{}'::jsonb, ?)
                """, discrepancyId, tenantId, invalidatingRunId, invalidatingSnapshotId,
                oldCanonicalId, subjectId, timestamp(NOW));
        jdbc.update("""
                INSERT INTO reconciliation.reconciliation_results
                    (result_id, tenant_id, run_id, snapshot_id, occurrence_id,
                     canonical_record_version_id, subject_type, subject_id,
                     result_status, discrepancy_category, provider_values,
                     internal_values, created_at)
                VALUES (?, ?, ?, ?, ?, ?, 'PAYMENT', ?, 'MATCHED', NULL,
                        '{}'::jsonb, '{}'::jsonb, ?)
                """, UUID.randomUUID(), tenantId, correctedRunId, correctedSnapshotId,
                correctedOccurrenceId, correctedCanonicalId, subjectId, timestamp(NOW));

        insertSettlementInstruction(originalSettlementPostingId, tenantId, oldCanonicalId,
                originalOccurrenceId, subjectId, originalRunId);
        insertLedgerAccountsAndOriginalTransaction(tenantId, originalLedgerTransactionId);
        jdbc.update("""
                INSERT INTO reconciliation.settlement_posting_applications
                    (settlement_posting_id, tenant_id, status, ledger_transaction_id,
                     posted_at, updated_at)
                VALUES (?, ?, 'POSTED', ?, ?, ?)
                """, originalSettlementPostingId, tenantId, originalLedgerTransactionId,
                timestamp(NOW), timestamp(NOW));

        jdbc.update("""
                INSERT INTO casework.cases
                    (id, tenant_id, source_category, source_id, related_payment_id,
                     severity, due_at, status, owner_id, resolution, resolution_note,
                     corrective_action_required, corrective_action_completed, created_at, updated_at)
                VALUES (?, ?, 'RECONCILIATION_DISCREPANCY', ?, ?, 'HIGH', ?, 'INVESTIGATING',
                        NULL, NULL, NULL, FALSE, FALSE, ?, ?)
                """, caseId, tenantId, discrepancyId, subjectId, timestamp(NOW.plusSeconds(3600)),
                timestamp(NOW), timestamp(NOW));

        return new Fixture(tenantId, familyId, correctedRunId, subjectId, caseId, discrepancyId,
                originalSettlementPostingId, originalLedgerTransactionId, actorId, correlationId);
    }

    private void insertBatchFamily(UUID tenantId, UUID familyId) {
        jdbc.update("""
                INSERT INTO reconciliation.settlement_batch_families
                    (family_id, tenant_id, provider_id, provider_batch_reference,
                     settlement_period_start, settlement_period_end, created_at)
                VALUES (?, ?, 'SIMULATOR', ?, ?, ?, ?)
                """, familyId, tenantId, "batch-" + familyId,
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31), timestamp(NOW));
    }

    private void insertBatchVersion(UUID batchVersionId, UUID familyId, String hash,
                                    UUID supersedesBatchVersionId) {
        jdbc.update("""
                INSERT INTO reconciliation.settlement_batch_versions
                    (batch_version_id, family_id, raw_file_sha256, object_key, byte_size,
                     status, supersedes_batch_version_id, total_rows, valid_rows, invalid_rows,
                     created_at, updated_at)
                VALUES (?, ?, ?, ?, 10, 'COMPLETED', ?, 1, 1, 0, ?, ?)
                """, batchVersionId, familyId, hash, "settlements/" + batchVersionId,
                supersedesBatchVersionId, timestamp(NOW), timestamp(NOW));
    }

    private void insertSnapshot(UUID snapshotId, UUID tenantId, UUID familyId, UUID batchVersionId,
                                int runNumber, String hashPrefix) {
        jdbc.update("""
                INSERT INTO reconciliation.reconciliation_snapshots
                    (snapshot_id, tenant_id, batch_family_id, batch_version_id,
                     run_number, rules_version, source_cutoff, snapshot_status,
                     snapshot_sha256, captured_record_count, captured_fact_count,
                     created_at, completed_at)
                VALUES (?, ?, ?, ?, ?, 'release-0.3-reconciliation-v1', ?, 'COMPLETE', ?, 1, 1, ?, ?)
                """, snapshotId, tenantId, familyId, batchVersionId, runNumber,
                timestamp(NOW), hashPrefix.repeat(64), timestamp(NOW), timestamp(NOW));
    }

    private void insertBuildingSnapshot(UUID snapshotId, UUID tenantId, UUID familyId,
                                         UUID batchVersionId, int runNumber) {
        jdbc.update("""
                INSERT INTO reconciliation.reconciliation_snapshots
                    (snapshot_id, tenant_id, batch_family_id, batch_version_id,
                     run_number, rules_version, source_cutoff, snapshot_status,
                     captured_record_count, captured_fact_count, created_at)
                VALUES (?, ?, ?, ?, ?, 'release-0.3-reconciliation-v1', ?, 'BUILDING', 0, 0, ?)
                """, snapshotId, tenantId, familyId, batchVersionId, runNumber,
                timestamp(NOW), timestamp(NOW));
    }

    private void insertRun(UUID runId, UUID tenantId, UUID familyId, UUID batchVersionId,
                           UUID snapshotId, int runNumber, String status, int discrepancyCount) {
        jdbc.update("""
                INSERT INTO reconciliation.reconciliation_runs
                    (run_id, tenant_id, batch_family_id, batch_version_id, snapshot_id,
                     run_number, rules_version, source_cutoff, status,
                     matched_count, unmatched_count, discrepancy_count,
                     created_at, started_at, terminal_at)
                VALUES (?, ?, ?, ?, ?, ?, 'release-0.3-reconciliation-v1', ?, ?,
                        1, 0, ?, ?, ?, ?)
                """, runId, tenantId, familyId, batchVersionId, snapshotId, runNumber,
                timestamp(NOW), status, discrepancyCount, timestamp(NOW),
                timestamp(NOW), timestamp(NOW));
    }

    private void insertCanonical(UUID canonicalId, UUID tenantId, String providerKey, String hashPrefix) {
        jdbc.update("""
                INSERT INTO reconciliation.canonical_settlement_record_versions
                    (canonical_record_version_id, tenant_id, provider_id,
                     provider_record_key, normalized_content_hash, normalized_content, created_at)
                VALUES (?, ?, 'SIMULATOR', ?, ?, '{}'::jsonb, ?)
                """, canonicalId, tenantId, providerKey, hashPrefix.repeat(64), timestamp(NOW));
    }

    private void insertOccurrence(UUID batchVersionId, UUID occurrenceId, UUID tenantId,
                                  UUID canonicalId, String hashPrefix) {
        jdbc.update("""
                INSERT INTO reconciliation.settlement_record_occurrences
                    (occurrence_id, batch_version_id, tenant_id, row_number,
                     provider_record_key, normalized_content_hash, normalized_content,
                     canonical_record_version_id, validation_state, created_at)
                VALUES (?, ?, ?, 1, 'settlement-key', ?, '{}'::jsonb, ?, 'VALID', ?)
                """, occurrenceId, batchVersionId, tenantId, hashPrefix.repeat(64),
                canonicalId, timestamp(NOW));
    }

    private void insertSnapshotOccurrence(UUID snapshotId, UUID batchVersionId, UUID occurrenceId,
                                          UUID canonicalId, UUID tenantId) {
        jdbc.update("""
                INSERT INTO reconciliation.snapshot_settlement_records
                    (snapshot_id, tenant_id, batch_version_id, occurrence_id,
                     canonical_record_version_id, row_number, provider_record_key,
                     normalized_content_hash, normalized_content, validation_state, captured_at)
                VALUES (?, ?, ?, ?, ?, 1, 'settlement-key', ?, '{}'::jsonb, 'VALID', ?)
                """, snapshotId, tenantId, batchVersionId, occurrenceId, canonicalId,
                "1".repeat(64), timestamp(NOW));
    }

    private void insertSnapshotSubject(UUID snapshotId, UUID batchVersionId, UUID tenantId,
                                       UUID subjectId, UUID merchantId) {
        jdbc.update("""
                INSERT INTO reconciliation.snapshot_financial_subjects
                    (snapshot_id, tenant_id, batch_version_id, subject_type, subject_id,
                     payment_id, merchant_id, amount, currency, provider_id,
                     provider_idempotency_key, provider_evidence_id, provider_result_id,
                     provider_reference, provider_result_category, financial_status, applied_at,
                     provider_evidence, ledger_evidence, captured_at)
                VALUES (?, ?, ?, 'PAYMENT', ?, ?, ?, 10.00, 'SAR', 'SIMULATOR',
                        ?, ?, ?, 'provider-reference', 'SUCCESS', 'COMPLETED', ?,
                        '{}'::jsonb, '{}'::jsonb, ?)
                """, snapshotId, tenantId, batchVersionId, subjectId, subjectId, merchantId,
                "idempotency-" + subjectId, UUID.randomUUID(), UUID.randomUUID(),
                timestamp(NOW), timestamp(NOW));
    }

    private void insertSettlementInstruction(UUID postingId, UUID tenantId, UUID canonicalId,
                                              UUID occurrenceId, UUID subjectId, UUID runId) {
        jdbc.update("""
                INSERT INTO reconciliation.settlement_posting_instructions
                    (settlement_posting_id, tenant_id, canonical_record_version_id,
                     occurrence_id, subject_type, subject_id, template_version, run_id,
                     amount, currency, instruction_hash, created_at)
                VALUES (?, ?, ?, ?, 'PAYMENT', ?, 'release-0.3-v1', ?, 10.00, 'SAR', ?, ?)
                """, postingId, tenantId, canonicalId, occurrenceId, subjectId, runId,
                "a".repeat(64), timestamp(NOW));
    }

    private void insertLedgerAccountsAndOriginalTransaction(UUID tenantId, UUID transactionId) {
        new TransactionTemplate(transactionManager).execute(status -> {
            UUID debitAccount = UUID.randomUUID();
            UUID creditAccount = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO ledger.accounts (id, tenant_id, account_code, currency, status, created_at)
                    VALUES (?, ?, 'SETTLEMENT_RECEIVABLE', 'SAR', 'ACTIVE', ?),
                           (?, ?, 'PROVIDER_CLEARING', 'SAR', 'ACTIVE', ?)
                    """, debitAccount, tenantId, timestamp(NOW), creditAccount, tenantId, timestamp(NOW));
            jdbc.update("""
                    INSERT INTO ledger.transactions
                        (id, tenant_id, source_type, source_id, compensates_transaction_id,
                         posted_at, currency, entry_count, debit_total, credit_total)
                    VALUES (?, ?, 'SETTLEMENT_ADJUSTMENT', ?, NULL, ?, 'SAR', 2, 10.00, 10.00)
                    """, transactionId, tenantId, UUID.randomUUID(), timestamp(NOW));
            jdbc.update("""
                    INSERT INTO ledger.entries
                        (tenant_id, transaction_id, entry_index, account_id, direction, amount, currency)
                    VALUES (?, ?, 0, ?, 'DEBIT', 10.00, 'SAR'),
                           (?, ?, 1, ?, 'CREDIT', 10.00, 'SAR')
                    """, tenantId, transactionId, debitAccount, tenantId, transactionId, creditAccount);
            return null;
        });
    }

    private static Timestamp timestamp(Instant value) {
        return Timestamp.from(value);
    }

    private record Fixture(
            UUID tenantId,
            UUID batchFamilyId,
            UUID correctedRunId,
            UUID subjectId,
            UUID caseId,
            UUID discrepancyId,
            UUID originalSettlementPostingId,
            UUID originalLedgerTransactionId,
            UUID actorId,
            UUID correlationId
    ) {
    }
}
