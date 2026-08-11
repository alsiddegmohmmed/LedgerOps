package com.ledgerops.reconciliation.infrastructure;

import com.ledgerops.reconciliation.application.SettlementBatchStore;
import com.ledgerops.reconciliation.domain.SettlementBatchIdentity;
import com.ledgerops.support.PostgresTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(PostgresTestConfiguration.class)
class SettlementSchemaIntegrationTests {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SettlementBatchStore store;

    @Test
    void createsSpringBatchAndReconciliationTablesThroughFlyway() {
        assertThat(tableExists(null, "BATCH_JOB_INSTANCE")).isTrue();
        assertThat(tableExists("reconciliation", "settlement_batch_families")).isTrue();
        assertThat(tableExists("reconciliation", "settlement_batch_versions")).isTrue();
        assertThat(tableExists("reconciliation", "settlement_record_occurrences")).isTrue();
        assertThat(tableExists("reconciliation", "canonical_settlement_record_versions")).isTrue();
        assertThat(tableExists("reconciliation", "batch_family_controls")).isTrue();
        assertThat(tableExists("reconciliation", "reconciliation_snapshots")).isTrue();
        assertThat(tableExists("reconciliation", "reconciliation_runs")).isTrue();
        assertThat(tableExists("reconciliation", "current_reconciliation_runs")).isTrue();
        assertThat(tableExists("reconciliation", "snapshot_settlement_records")).isTrue();
        assertThat(tableExists("reconciliation", "snapshot_financial_subjects")).isTrue();
        assertThat(tableExists("reconciliation", "reconciliation_results")).isTrue();
        assertThat(tableExists("reconciliation", "reconciliation_subject_status_history")).isTrue();
        assertThat(tableExists("reconciliation", "current_reconciliation_subject_status")).isTrue();
        assertThat(tableExists("reconciliation", "settlement_posting_instructions")).isTrue();
        assertThat(tableExists("reconciliation", "settlement_posting_applications")).isTrue();
        assertThat(tableExists("reconciliation", "settlement_posting_failures")).isTrue();
    }

    @Test
    void exactDuplicateReturnsTheExistingImmutableVersion() {
        UUID tenantId = UUID.randomUUID();
        SettlementBatchIdentity identity = new SettlementBatchIdentity(
                tenantId, "SIMULATOR", "batch-duplicate", LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31));
        Instant now = Instant.parse("2026-08-11T00:00:00Z");
        UUID firstId = UUID.randomUUID();
        UUID secondId = UUID.randomUUID();

        var first = store.insertReceived(firstId, identity, "a".repeat(64),
                "settlements/" + tenantId + "/" + "a".repeat(64), 128, null, null, now);
        var duplicate = store.insertReceived(secondId, identity, "a".repeat(64),
                "settlements/" + tenantId + "/" + "a".repeat(64), 128, null, null, now.plusSeconds(1));

        assertThat(duplicate.batchVersionId()).isEqualTo(first.batchVersionId());
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM reconciliation.settlement_batch_versions WHERE family_id = ?",
                Integer.class, first.familyId())).isEqualTo(1);
    }

    @Test
    void databaseEnforcesCanonicalIdentityAndImmutableBatchContentIdentity() {
        assertThat(constraintExists("uk_settlement_batch_content")).isTrue();
        assertThat(constraintExists("uk_settlement_canonical_identity")).isTrue();
        assertThat(constraintExists("uk_settlement_occurrence_position")).isTrue();
        assertThat(constraintExists("fk_settlement_batch_supersedes_same_family")).isTrue();
        assertThat(constraintExists("uk_reconciliation_run_family_number")).isTrue();
        assertThat(constraintExists("pk_current_reconciliation_run")).isTrue();
        assertThat(constraintExists("pk_snapshot_settlement_record")).isTrue();
        assertThat(constraintExists("uk_snapshot_settlement_record_row")).isTrue();
        assertThat(constraintExists("pk_snapshot_financial_subject")).isTrue();
        assertThat(constraintExists("pk_current_reconciliation_subject_status")).isTrue();
        assertThat(constraintExists("uk_settlement_instruction_identity")).isTrue();
        assertThat(constraintExists("settlement_posting_applications_pkey")).isTrue();
    }

    @Test
    void eachBatchFamilyGetsTheFirstLockRowUsedBySliceEightOperations() {
        UUID tenantId = UUID.randomUUID();
        SettlementBatchIdentity identity = new SettlementBatchIdentity(
                tenantId, "SIMULATOR", "batch-control", LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 31));

        var batch = store.insertReceived(
                UUID.randomUUID(), identity, "b".repeat(64),
                "settlements/" + tenantId + "/b", 64, null, null,
                Instant.parse("2026-08-11T00:00:00Z"));

        assertThat(jdbc.queryForObject("""
                SELECT count(*)
                  FROM reconciliation.batch_family_controls
                 WHERE tenant_id = ? AND batch_family_id = ?
                """, Integer.class, tenantId, batch.familyId())).isEqualTo(1);
    }

    @Test
    void snapshotCanBeBuiltAndFinalizedButIsImmutableAfterCompletion() {
        UUID tenantId = UUID.randomUUID();
        SettlementBatchIdentity identity = new SettlementBatchIdentity(
                tenantId, "SIMULATOR", "batch-snapshot", LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 31));
        var batch = store.insertReceived(
                UUID.randomUUID(), identity, "c".repeat(64),
                "settlements/" + tenantId + "/c", 64, null, null,
                Instant.parse("2026-08-11T00:00:00Z"));
        UUID snapshotId = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-11T00:00:00Z");
        jdbc.update("""
                INSERT INTO reconciliation.reconciliation_snapshots
                    (snapshot_id, tenant_id, batch_family_id, batch_version_id,
                     run_number, rules_version, source_cutoff, snapshot_status,
                     snapshot_sha256, captured_record_count, captured_fact_count,
                     created_at, completed_at, failure_reason)
                VALUES (?, ?, ?, ?, 1, 'release-0.3-reconciliation-v1', ?, 'BUILDING', NULL, 0, 0, ?, NULL, NULL)
                """, snapshotId, tenantId, batch.familyId(), batch.batchVersionId(),
                java.sql.Timestamp.from(now), java.sql.Timestamp.from(now));

        jdbc.update("""
                UPDATE reconciliation.reconciliation_snapshots
                   SET snapshot_status = 'COMPLETE', snapshot_sha256 = ?, completed_at = ?,
                       captured_record_count = 1, captured_fact_count = 2
                 WHERE snapshot_id = ?
                """, "d".repeat(64), java.sql.Timestamp.from(now.plusSeconds(1)), snapshotId);

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE reconciliation.reconciliation_snapshots SET rules_version = ? WHERE snapshot_id = ?",
                "changed", snapshotId)).isInstanceOf(Exception.class);
        assertThatThrownBy(() -> jdbc.update(
                "DELETE FROM reconciliation.reconciliation_snapshots WHERE snapshot_id = ?", snapshotId))
                .isInstanceOf(Exception.class);
    }

    @Test
    void buildingSnapshotRejectsFinalizationWithoutHashAndCompletedSnapshotRejectsChildRows() {
        UUID tenantId = UUID.randomUUID();
        SettlementBatchIdentity identity = new SettlementBatchIdentity(
                tenantId, "SIMULATOR", "batch-snapshot-state", LocalDate.of(2026, 8, 1),
                LocalDate.of(2026, 8, 31));
        var batch = store.insertReceived(
                UUID.randomUUID(), identity, "e".repeat(64),
                "settlements/" + tenantId + "/e", 64, null, null,
                Instant.parse("2026-08-11T00:00:00Z"));
        UUID snapshotId = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-11T00:00:00Z");
        jdbc.update("""
                INSERT INTO reconciliation.reconciliation_snapshots
                    (snapshot_id, tenant_id, batch_family_id, batch_version_id,
                     run_number, rules_version, source_cutoff, snapshot_status,
                     captured_record_count, captured_fact_count, created_at)
                VALUES (?, ?, ?, ?, 1, 'release-0.3-reconciliation-v1', ?, 'BUILDING', 0, 0, ?)
                """, snapshotId, tenantId, batch.familyId(), batch.batchVersionId(),
                java.sql.Timestamp.from(now), java.sql.Timestamp.from(now));

        assertThatThrownBy(() -> jdbc.update("""
                UPDATE reconciliation.reconciliation_snapshots
                   SET snapshot_status = 'COMPLETE', completed_at = ?
                 WHERE snapshot_id = ?
                """, java.sql.Timestamp.from(now.plusSeconds(1)), snapshotId))
                .isInstanceOf(Exception.class);

        jdbc.update("""
                UPDATE reconciliation.reconciliation_snapshots
                   SET snapshot_status = 'COMPLETE', snapshot_sha256 = ?, completed_at = ?
                 WHERE snapshot_id = ?
                """, "f".repeat(64), java.sql.Timestamp.from(now.plusSeconds(1)), snapshotId);

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO reconciliation.snapshot_settlement_records
                    (snapshot_id, tenant_id, batch_version_id, occurrence_id,
                     canonical_record_version_id, row_number, provider_record_key,
                     normalized_content_hash, normalized_content, captured_at)
                VALUES (?, ?, ?, ?, ?, 1, 'provider-key', ?, '{}'::jsonb, ?)
                """, snapshotId, tenantId, batch.batchVersionId(), UUID.randomUUID(),
                UUID.randomUUID(), "a".repeat(64), java.sql.Timestamp.from(now)))
                .isInstanceOf(Exception.class);
    }

    @Test
    void correctedVersionCannotPointAtAnotherBatchFamily() {
        UUID tenantId = UUID.randomUUID();
        Instant now = Instant.parse("2026-08-11T00:00:00Z");
        SettlementBatchIdentity firstIdentity = new SettlementBatchIdentity(
                tenantId, "SIMULATOR", "batch-one", LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31));
        SettlementBatchIdentity otherIdentity = new SettlementBatchIdentity(
                tenantId, "SIMULATOR", "batch-two", LocalDate.of(2026, 8, 1), LocalDate.of(2026, 8, 31));
        var first = store.insertReceived(UUID.randomUUID(), firstIdentity, "c".repeat(64),
                "settlements/" + tenantId + "/c", 64, null, null, now);

        assertThatThrownBy(() -> store.insertReceived(UUID.randomUUID(), otherIdentity, "d".repeat(64),
                "settlements/" + tenantId + "/d", 64, first.batchVersionId(), null, now))
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }

    private boolean tableExists(String schema, String table) {
        if (schema == null) {
            return Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT to_regclass(?) IS NOT NULL", Boolean.class, table));
        }
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM information_schema.tables
                     WHERE table_schema = ? AND table_name = ?
                )
                """, Boolean.class, schema, table));
    }

    private boolean constraintExists(String name) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM pg_constraint WHERE conname = ?", Integer.class, name) > 0;
    }
}
