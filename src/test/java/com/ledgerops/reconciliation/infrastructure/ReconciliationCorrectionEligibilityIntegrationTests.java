package com.ledgerops.reconciliation.infrastructure;

import com.ledgerops.reconciliation.api.ReconciliationCorrectionError;
import com.ledgerops.reconciliation.api.ReconciliationCorrectionException;
import com.ledgerops.reconciliation.api.ReconciliationCorrectionPort;
import com.ledgerops.support.PostgresTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
class ReconciliationCorrectionEligibilityIntegrationTests {

    private static final Instant NOW = Instant.parse("2026-08-11T09:00:00Z");

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ReconciliationCorrectionPort corrections;

    @Test
    @Transactional
    void acceptsAnInvalidatedPostedSettlementAdjustmentForTheCurrentTenant() {
        Fixture fixture = seed();

        var result = corrections.lockAndCheck(
                fixture.tenantId(), fixture.discrepancyId(),
                fixture.settlementPostingId(), fixture.ledgerTransactionId());

        assertThat(result.tenantId()).isEqualTo(fixture.tenantId());
        assertThat(result.currentRunId()).isEqualTo(fixture.currentRunId());
        assertThat(result.invalidatingRunId()).isEqualTo(fixture.invalidatingRunId());
        assertThat(result.discrepancyId()).isEqualTo(fixture.discrepancyId());
        assertThat(result.originalLedgerTransactionId()).isEqualTo(fixture.ledgerTransactionId());
    }

    @Test
    @Transactional
    void rejectsASettlementPostingThatHasNotBeenPosted() {
        Fixture fixture = seed();
        jdbc.update("""
                UPDATE reconciliation.settlement_posting_applications
                   SET status = 'PENDING', ledger_transaction_id = NULL, posted_at = NULL
                 WHERE settlement_posting_id = ?
                """, fixture.settlementPostingId());

        assertThatThrownBy(() -> corrections.lockAndCheck(
                fixture.tenantId(), fixture.discrepancyId(),
                fixture.settlementPostingId(), fixture.ledgerTransactionId()))
                .isInstanceOfSatisfying(ReconciliationCorrectionException.class,
                        exception -> assertThat(exception.error())
                                .isEqualTo(ReconciliationCorrectionError.DISCREPANCY_NOT_ELIGIBLE));
    }

    @Test
    @Transactional
    void rejectsAResultThatIsNotAnInvalidatingDiscrepancy() {
        Fixture fixture = seed("MATCHED", null);

        assertThatThrownBy(() -> corrections.lockAndCheck(
                fixture.tenantId(), fixture.discrepancyId(),
                fixture.settlementPostingId(), fixture.ledgerTransactionId()))
                .isInstanceOfSatisfying(ReconciliationCorrectionException.class,
                        exception -> assertThat(exception.error())
                                .isEqualTo(ReconciliationCorrectionError.DISCREPANCY_NOT_ELIGIBLE));
    }

    @Test
    @Transactional
    void rejectsASettlementPostingFromAnotherTenant() {
        Fixture fixture = seed();

        assertThatThrownBy(() -> corrections.lockAndCheck(
                UUID.randomUUID(), fixture.discrepancyId(),
                fixture.settlementPostingId(), fixture.ledgerTransactionId()))
                .isInstanceOfSatisfying(ReconciliationCorrectionException.class,
                        exception -> assertThat(exception.error())
                                .isEqualTo(ReconciliationCorrectionError.TARGET_NOT_FOUND));
    }

    private Fixture seed() {
        return seed("DISCREPANCY", "AMOUNT_MISMATCH");
    }

    private Fixture seed(String resultStatus, String discrepancyCategory) {
        UUID tenantId = UUID.randomUUID();
        UUID familyId = UUID.randomUUID();
        UUID batchVersionId = UUID.randomUUID();
        UUID currentSnapshotId = UUID.randomUUID();
        UUID invalidatingSnapshotId = UUID.randomUUID();
        UUID currentRunId = UUID.randomUUID();
        UUID invalidatingRunId = UUID.randomUUID();
        UUID currentPointerSubjectId = UUID.randomUUID();
        UUID canonicalRecordVersionId = UUID.randomUUID();
        UUID settlementPostingId = UUID.randomUUID();
        UUID ledgerTransactionId = UUID.randomUUID();
        UUID discrepancyId = UUID.randomUUID();

        jdbc.update("""
                INSERT INTO reconciliation.settlement_batch_families
                    (family_id, tenant_id, provider_id, provider_batch_reference,
                     settlement_period_start, settlement_period_end, created_at)
                VALUES (?, ?, 'SIMULATOR', ?, ?, ?, ?)
                """, familyId, tenantId, "batch-" + familyId,
                LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31), timestamp(NOW));
        jdbc.update("""
                INSERT INTO reconciliation.settlement_batch_versions
                    (batch_version_id, family_id, raw_file_sha256, object_key, byte_size,
                     status, total_rows, valid_rows, invalid_rows, created_at, updated_at)
                VALUES (?, ?, ?, ?, 10, 'COMPLETED', 1, 1, 0, ?, ?)
                """, batchVersionId, familyId, "a".repeat(64),
                "settlements/" + tenantId + "/" + batchVersionId, timestamp(NOW), timestamp(NOW));
        jdbc.update("""
                INSERT INTO reconciliation.batch_family_controls
                    (batch_family_id, tenant_id, created_at)
                VALUES (?, ?, ?)
                """, familyId, tenantId, timestamp(NOW));
        insertSnapshot(currentSnapshotId, tenantId, familyId, batchVersionId, 1, "b");
        insertSnapshot(invalidatingSnapshotId, tenantId, familyId, batchVersionId, 2, "c");
        insertRun(currentRunId, tenantId, familyId, batchVersionId, currentSnapshotId,
                1, "COMPLETED", 0);
        insertRun(invalidatingRunId, tenantId, familyId, batchVersionId, invalidatingSnapshotId,
                2, "COMPLETED_WITH_DISCREPANCIES", 1);
        jdbc.update("""
                INSERT INTO reconciliation.current_reconciliation_runs
                    (tenant_id, batch_family_id, run_id, promoted_at, updated_at)
                VALUES (?, ?, ?, ?, ?)
                """, tenantId, familyId, currentRunId, timestamp(NOW), timestamp(NOW));
        jdbc.update("""
                INSERT INTO reconciliation.canonical_settlement_record_versions
                    (canonical_record_version_id, tenant_id, provider_id,
                     provider_record_key, normalized_content_hash, normalized_content, created_at)
                VALUES (?, ?, 'SIMULATOR', ?, ?, '{}'::jsonb, ?)
                """, canonicalRecordVersionId, tenantId, "record-" + canonicalRecordVersionId,
                "d".repeat(64), timestamp(NOW));
        jdbc.update("""
                INSERT INTO reconciliation.reconciliation_results
                    (result_id, tenant_id, run_id, snapshot_id, occurrence_id,
                     canonical_record_version_id, subject_type, subject_id,
                     result_status, discrepancy_category, provider_values,
                     internal_values, created_at)
                VALUES (?, ?, ?, ?, NULL, ?, 'PAYMENT', ?, ?,
                        ?, '{}'::jsonb, '{}'::jsonb, ?)
                """, discrepancyId, tenantId, invalidatingRunId, invalidatingSnapshotId,
                canonicalRecordVersionId, currentPointerSubjectId, resultStatus,
                discrepancyCategory, timestamp(NOW));
        jdbc.update("""
                INSERT INTO reconciliation.settlement_posting_instructions
                    (settlement_posting_id, tenant_id, canonical_record_version_id,
                     occurrence_id, subject_type, subject_id, template_version, run_id,
                     amount, currency, instruction_hash, created_at)
                VALUES (?, ?, ?, ?, 'PAYMENT', ?, 'release-0.3-v1', ?, 10.00, 'SAR', ?, ?)
                """, settlementPostingId, tenantId, canonicalRecordVersionId, UUID.randomUUID(),
                currentPointerSubjectId, currentRunId, "e".repeat(64), timestamp(NOW));
        jdbc.update("""
                INSERT INTO reconciliation.settlement_posting_applications
                    (settlement_posting_id, tenant_id, status, ledger_transaction_id,
                     posted_at, updated_at)
                VALUES (?, ?, 'POSTED', ?, ?, ?)
                """, settlementPostingId, tenantId, ledgerTransactionId,
                timestamp(NOW), timestamp(NOW));

        return new Fixture(tenantId, currentRunId, invalidatingRunId, discrepancyId,
                settlementPostingId, ledgerTransactionId);
    }

    private void insertSnapshot(UUID snapshotId, UUID tenantId, UUID familyId,
                                UUID batchVersionId, int runNumber, String hashPrefix) {
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

    private void insertRun(UUID runId, UUID tenantId, UUID familyId, UUID batchVersionId,
                           UUID snapshotId, int runNumber, String status, int discrepancyCount) {
        jdbc.update("""
                INSERT INTO reconciliation.reconciliation_runs
                    (run_id, tenant_id, batch_family_id, batch_version_id, snapshot_id,
                     run_number, rules_version, source_cutoff, status,
                     matched_count, unmatched_count, discrepancy_count,
                     created_at, started_at, terminal_at)
                VALUES (?, ?, ?, ?, ?, ?, 'release-0.3-reconciliation-v1', ?, ?,
                        0, 0, ?, ?, ?, ?)
                """, runId, tenantId, familyId, batchVersionId, snapshotId, runNumber,
                timestamp(NOW), status, discrepancyCount, timestamp(NOW),
                timestamp(NOW), timestamp(NOW));
    }

    private static Timestamp timestamp(Instant value) {
        return Timestamp.from(value);
    }

    private record Fixture(
            UUID tenantId,
            UUID currentRunId,
            UUID invalidatingRunId,
            UUID discrepancyId,
            UUID settlementPostingId,
            UUID ledgerTransactionId
    ) {
    }
}
