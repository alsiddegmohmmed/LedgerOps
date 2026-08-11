package com.ledgerops.reconciliation.infrastructure;

import com.ledgerops.reconciliation.api.ReconciliationCurrentRunSnapshot;
import com.ledgerops.reconciliation.api.ReconciliationPostingSnapshot;
import com.ledgerops.reconciliation.api.ReconciliationResultSnapshot;
import com.ledgerops.reconciliation.api.ReconciliationRunSnapshot;
import com.ledgerops.reconciliation.api.ReconciliationStatusHistorySnapshot;
import com.ledgerops.reconciliation.application.ReconciliationRunQuery;
import com.ledgerops.reconciliation.domain.ReconciliationDiscrepancyCategory;
import com.ledgerops.reconciliation.domain.ReconciliationRunStatus;
import com.ledgerops.reconciliation.domain.ReconciliationStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class JdbcReconciliationRunQuery implements ReconciliationRunQuery {

    private final JdbcTemplate jdbc;

    JdbcReconciliationRunQuery(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<ReconciliationRunSnapshot> findRuns(
            UUID tenantId,
            Optional<UUID> batchFamilyId,
            int limit
    ) {
        StringBuilder sql = new StringBuilder("""
                SELECT run_id, tenant_id, batch_family_id, batch_version_id, snapshot_id,
                       run_number, rules_version, source_cutoff, status, matched_count,
                       unmatched_count, discrepancy_count, created_at, started_at,
                       terminal_at, failure_reason
                  FROM reconciliation.reconciliation_runs
                 WHERE tenant_id = ?
                """);
        List<Object> arguments = new ArrayList<>(List.of(tenantId));
        batchFamilyId.ifPresent(family -> {
            sql.append(" AND batch_family_id = ?");
            arguments.add(family);
        });
        sql.append(" ORDER BY created_at DESC, run_number DESC, run_id DESC LIMIT ?");
        arguments.add(limit);
        return jdbc.query(sql.toString(), (rs, row) -> run(rs), arguments.toArray());
    }

    @Override
    public Optional<ReconciliationRunSnapshot> findRun(UUID tenantId, UUID runId) {
        return jdbc.query("""
                SELECT run_id, tenant_id, batch_family_id, batch_version_id, snapshot_id,
                       run_number, rules_version, source_cutoff, status, matched_count,
                       unmatched_count, discrepancy_count, created_at, started_at,
                       terminal_at, failure_reason
                  FROM reconciliation.reconciliation_runs
                 WHERE tenant_id = ? AND run_id = ?
                """, rs -> rs.next() ? Optional.of(run(rs, 0)) : Optional.empty(), tenantId, runId);
    }

    @Override
    public List<ReconciliationResultSnapshot> findResults(
            UUID tenantId,
            UUID runId,
            Optional<String> resultStatus,
            Optional<String> discrepancyCategory,
            int limit,
            int offset
    ) {
        StringBuilder sql = new StringBuilder("""
                SELECT result_id, occurrence_id, canonical_record_version_id,
                       subject_type, subject_id, result_status, discrepancy_category,
                       provider_values::text, internal_values::text, created_at
                  FROM reconciliation.reconciliation_results
                 WHERE tenant_id = ? AND run_id = ?
                """);
        List<Object> arguments = new ArrayList<>(List.of(tenantId, runId));
        resultStatus.ifPresent(value -> {
            sql.append(" AND result_status = ?");
            arguments.add(value);
        });
        discrepancyCategory.ifPresent(value -> {
            sql.append(" AND discrepancy_category = ?");
            arguments.add(value);
        });
        sql.append(" ORDER BY created_at ASC, result_id ASC LIMIT ? OFFSET ?");
        arguments.add(limit);
        arguments.add(offset);
        return jdbc.query(sql.toString(), (rs, row) -> new ReconciliationResultSnapshot(
                rs.getObject("result_id", UUID.class),
                rs.getObject("occurrence_id", UUID.class),
                rs.getObject("canonical_record_version_id", UUID.class),
                rs.getString("subject_type"),
                rs.getObject("subject_id", UUID.class),
                rs.getString("result_status"),
                enumValue(ReconciliationDiscrepancyCategory.class,
                        rs.getString("discrepancy_category")),
                rs.getString("provider_values"),
                rs.getString("internal_values"),
                timestamp(rs, "created_at")), arguments.toArray());
    }

    @Override
    public Optional<ReconciliationCurrentRunSnapshot> findCurrent(
            UUID tenantId,
            UUID batchFamilyId
    ) {
        return jdbc.query("""
                SELECT tenant_id, batch_family_id, run_id, promoted_at
                  FROM reconciliation.current_reconciliation_runs
                 WHERE tenant_id = ? AND batch_family_id = ?
                """, rs -> rs.next() ? Optional.of(new ReconciliationCurrentRunSnapshot(
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("batch_family_id", UUID.class),
                rs.getObject("run_id", UUID.class),
                timestamp(rs, "promoted_at"))) : Optional.empty(), tenantId, batchFamilyId);
    }

    @Override
    public List<ReconciliationStatusHistorySnapshot> findStatusHistory(
            UUID tenantId,
            String subjectType,
            UUID subjectId
    ) {
        return jdbc.query("""
                SELECT status_id, tenant_id, subject_type, subject_id, run_id, status, occurred_at
                  FROM reconciliation.reconciliation_subject_status_history
                 WHERE tenant_id = ? AND subject_type = ? AND subject_id = ?
                 ORDER BY occurred_at ASC, status_id ASC
                """, (rs, row) -> new ReconciliationStatusHistorySnapshot(
                rs.getObject("status_id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getString("subject_type"),
                rs.getObject("subject_id", UUID.class),
                rs.getObject("run_id", UUID.class),
                enumValue(ReconciliationStatus.class, rs.getString("status")),
                timestamp(rs, "occurred_at")), tenantId, subjectType, subjectId);
    }

    @Override
    public List<ReconciliationPostingSnapshot> findPostings(
            UUID tenantId,
            UUID runId,
            int limit,
            int offset
    ) {
        return jdbc.query("""
                SELECT i.settlement_posting_id, i.tenant_id, i.run_id,
                       i.canonical_record_version_id, i.occurrence_id,
                       i.subject_type, i.subject_id, i.template_version,
                       i.amount, i.currency, i.instruction_hash, a.status,
                       a.ledger_transaction_id, i.created_at, a.posted_at
                  FROM reconciliation.settlement_posting_instructions i
                  JOIN reconciliation.settlement_posting_applications a
                    ON a.settlement_posting_id = i.settlement_posting_id
                 WHERE i.tenant_id = ? AND i.run_id = ?
                 ORDER BY i.created_at ASC, i.settlement_posting_id ASC
                 LIMIT ? OFFSET ?
                """, (rs, row) -> new ReconciliationPostingSnapshot(
                rs.getObject("settlement_posting_id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("run_id", UUID.class),
                rs.getObject("canonical_record_version_id", UUID.class),
                rs.getObject("occurrence_id", UUID.class),
                rs.getString("subject_type"),
                rs.getObject("subject_id", UUID.class),
                rs.getString("template_version"),
                rs.getBigDecimal("amount"),
                Currency.getInstance(rs.getString("currency")),
                rs.getString("instruction_hash"),
                rs.getString("status"),
                rs.getObject("ledger_transaction_id", UUID.class),
                timestamp(rs, "created_at"),
                timestamp(rs, "posted_at")), tenantId, runId, limit, offset);
    }

    private ReconciliationRunSnapshot run(java.sql.ResultSet rs, int ignored) throws java.sql.SQLException {
        return run(rs);
    }

    private ReconciliationRunSnapshot run(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ReconciliationRunSnapshot(
                rs.getObject("run_id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("batch_family_id", UUID.class),
                rs.getObject("batch_version_id", UUID.class),
                rs.getObject("snapshot_id", UUID.class),
                rs.getInt("run_number"),
                rs.getString("rules_version"),
                timestamp(rs, "source_cutoff"),
                ReconciliationRunStatus.valueOf(rs.getString("status")),
                rs.getLong("matched_count"),
                rs.getLong("unmatched_count"),
                rs.getLong("discrepancy_count"),
                timestamp(rs, "created_at"),
                timestamp(rs, "started_at"),
                timestamp(rs, "terminal_at"),
                rs.getString("failure_reason"));
    }

    private static Instant timestamp(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String value) {
        return value == null ? null : Enum.valueOf(type, value);
    }
}
