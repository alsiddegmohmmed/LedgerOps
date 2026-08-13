package com.ledgerops.reconciliation.infrastructure;

import com.ledgerops.reconciliation.api.ReconciliationDiscrepancyOperationalSummary;
import com.ledgerops.reconciliation.api.ReconciliationOperationalSummaryQuery;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Reconciliation-owned adapter for the Reporting projection rebuild boundary. */
@Repository
class JdbcReconciliationOperationalSummaryStore implements ReconciliationOperationalSummaryQuery {

    private final JdbcTemplate jdbc;

    JdbcReconciliationOperationalSummaryStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<ReconciliationDiscrepancyOperationalSummary> findDiscrepancies(
            UUID tenantId,
            Instant fromInclusive,
            Instant toExclusive,
            Set<UUID> merchantIds
    ) {
        Objects.requireNonNull(tenantId, "Summary Tenant ID must not be null");
        Objects.requireNonNull(fromInclusive, "Summary period start must not be null");
        Objects.requireNonNull(toExclusive, "Summary period end must not be null");
        Objects.requireNonNull(merchantIds, "Summary Merchant IDs must not be null");
        if (!fromInclusive.isBefore(toExclusive)) {
            throw new IllegalArgumentException("Summary period start must be before its exclusive end");
        }
        if (merchantIds.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Summary Merchant IDs must not contain null");
        }

        StringBuilder sql = new StringBuilder("""
                SELECT result.result_id, result.tenant_id, result.subject_type,
                       result.subject_id, financial.merchant_id, result.created_at,
                       current_run.run_id IS NOT NULL AS current_reconciliation_run
                  FROM reconciliation.reconciliation_results result
                  LEFT JOIN reconciliation.snapshot_financial_subjects financial
                    ON financial.snapshot_id = result.snapshot_id
                   AND financial.subject_type = result.subject_type
                   AND financial.subject_id = result.subject_id
                  LEFT JOIN reconciliation.reconciliation_runs run
                    ON run.tenant_id = result.tenant_id AND run.run_id = result.run_id
                  LEFT JOIN reconciliation.current_reconciliation_runs current_run
                    ON current_run.tenant_id = run.tenant_id
                   AND current_run.batch_family_id = run.batch_family_id
                   AND current_run.run_id = run.run_id
                 WHERE result.tenant_id = ?
                   AND result.result_status = 'DISCREPANCY'
                   AND result.created_at >= ?
                   AND result.created_at < ?
                """);
        List<Object> arguments = new ArrayList<>(List.of(
                tenantId, Timestamp.from(fromInclusive), Timestamp.from(toExclusive)));
        if (!merchantIds.isEmpty()) {
            sql.append(" AND financial.merchant_id IN (");
            for (int index = 0; index < merchantIds.size(); index++) {
                if (index > 0) {
                    sql.append(", ");
                }
                sql.append("?");
            }
            sql.append(")");
            arguments.addAll(merchantIds);
        }
        sql.append(" ORDER BY result.created_at ASC, result.result_id ASC");
        return jdbc.query(sql.toString(), (rs, row) ->
                new ReconciliationDiscrepancyOperationalSummary(
                        rs.getObject("result_id", UUID.class),
                        rs.getObject("tenant_id", UUID.class),
                        rs.getString("subject_type"),
                        rs.getObject("subject_id", UUID.class),
                        rs.getObject("merchant_id", UUID.class),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getBoolean("current_reconciliation_run")), arguments.toArray());
    }
}
