package com.ledgerops.casework.infrastructure;

import com.ledgerops.casework.api.CorrectionRequestCommand;
import com.ledgerops.casework.api.CorrectionRequestSnapshot;
import com.ledgerops.casework.application.CorrectionApplicationService;
import com.ledgerops.casework.domain.CorrectionRequestStatus;
import com.ledgerops.ledger.api.SettlementCorrectionLedger;
import com.ledgerops.ledger.api.SettlementCorrectionLedgerError;
import com.ledgerops.ledger.api.SettlementCorrectionLedgerException;
import com.ledgerops.support.PostgresTestConfiguration;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

@SpringBootTest
@Import({PostgresTestConfiguration.class, CorrectionApplicationFailureIntegrationTests.FailingLedgerConfiguration.class})
class CorrectionApplicationFailureIntegrationTests {

    private static final Instant NOW = Instant.parse("2026-08-11T09:00:00Z");

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private CorrectionApplicationService correction;

    @Autowired
    private SettlementCorrectionLedger ledger;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void persistsFailedCorrectionAfterTheLedgerTransactionRollsBack() {
        Fixture fixture = seed();
        doThrow(new SettlementCorrectionLedgerException(
                SettlementCorrectionLedgerError.POSTING_FAILED,
                "Injected Ledger posting failure"))
                .when(ledger).postCompensation(any());

        CorrectionRequestSnapshot result = correction.request(new CorrectionRequestCommand(
                fixture.tenantId(), fixture.caseId(), fixture.discrepancyId(),
                fixture.settlementPostingId(), fixture.ledgerTransactionId(),
                fixture.actorId(), "Correct the invalidated settlement", fixture.correlationId(), true));

        assertThat(result.status()).isEqualTo(CorrectionRequestStatus.FAILED);
        assertThat(result.failureReason()).isEqualTo("Injected Ledger posting failure");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM casework.correction_requests WHERE tenant_id = ? AND original_ledger_transaction_id = ?",
                String.class, fixture.tenantId(), fixture.ledgerTransactionId()))
                .isEqualTo("FAILED");
        assertThat(jdbc.queryForObject(
                "SELECT corrective_action_required FROM casework.cases WHERE tenant_id = ? AND id = ?",
                Boolean.class, fixture.tenantId(), fixture.caseId()))
                .isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM ledger.transactions WHERE tenant_id = ? AND source_type = 'AUTHORISED_CORRECTION'",
                Integer.class, fixture.tenantId()))
                .isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM audit.audit_records WHERE tenant_id = ? AND action_type = 'case.correction-failed' AND target_id = ?",
                Integer.class, fixture.tenantId(), result.correctionId().toString()))
                .isEqualTo(1);
    }

    private Fixture seed() {
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
        UUID caseId = UUID.randomUUID();
        UUID actorId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();

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
                VALUES (?, ?, ?, ?, NULL, ?, 'PAYMENT', ?, 'DISCREPANCY',
                        'AMOUNT_MISMATCH', '{}'::jsonb, '{}'::jsonb, ?)
                """, discrepancyId, tenantId, invalidatingRunId, invalidatingSnapshotId,
                canonicalRecordVersionId, currentPointerSubjectId, timestamp(NOW));
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
        jdbc.update("""
                INSERT INTO casework.cases
                    (id, tenant_id, source_category, source_id, related_payment_id,
                     severity, due_at, status, owner_id, resolution, resolution_note,
                     corrective_action_required, corrective_action_completed, created_at, updated_at)
                VALUES (?, ?, 'RECONCILIATION_DISCREPANCY', ?, NULL, 'HIGH', ?, 'INVESTIGATING',
                        NULL, NULL, NULL, FALSE, FALSE, ?, ?)
                """, caseId, tenantId, discrepancyId, timestamp(NOW.plusSeconds(3600)),
                timestamp(NOW), timestamp(NOW));
        insertLedgerTransaction(tenantId, ledgerTransactionId);
        return new Fixture(tenantId, caseId, discrepancyId, settlementPostingId,
                ledgerTransactionId, actorId, correlationId);
    }

    private void insertLedgerTransaction(UUID tenantId, UUID transactionId) {
        new TransactionTemplate(transactionManager).execute(status -> {
            UUID debitAccount = UUID.randomUUID();
            UUID creditAccount = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO ledger.accounts (id, tenant_id, account_code, currency, status, created_at)
                    VALUES (?, ?, 'SETTLEMENT_RECEIVABLE', 'SAR', 'ACTIVE', ?),
                           (?, ?, 'PROVIDER_CLEARING', 'SAR', 'ACTIVE', ?)
                    """, debitAccount, tenantId, timestamp(NOW),
                    creditAccount, tenantId, timestamp(NOW));
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
                    """, tenantId, transactionId, debitAccount,
                    tenantId, transactionId, creditAccount);
            return null;
        });
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

    @TestConfiguration(proxyBeanMethods = false)
    static class FailingLedgerConfiguration {
        @Bean
        @Primary
        SettlementCorrectionLedger failingSettlementCorrectionLedger() {
            return Mockito.mock(SettlementCorrectionLedger.class);
        }
    }

    private record Fixture(
            UUID tenantId,
            UUID caseId,
            UUID discrepancyId,
            UUID settlementPostingId,
            UUID ledgerTransactionId,
            UUID actorId,
            UUID correlationId
    ) {
    }
}
