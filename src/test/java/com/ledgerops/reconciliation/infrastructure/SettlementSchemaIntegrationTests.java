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
