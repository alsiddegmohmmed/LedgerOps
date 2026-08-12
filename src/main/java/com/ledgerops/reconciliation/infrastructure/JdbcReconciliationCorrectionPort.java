package com.ledgerops.reconciliation.infrastructure;

import com.ledgerops.reconciliation.api.ReconciliationCorrectionError;
import com.ledgerops.reconciliation.api.ReconciliationCorrectionException;
import com.ledgerops.reconciliation.api.ReconciliationCorrectionPort;
import com.ledgerops.reconciliation.api.SettlementCorrectionEligibility;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.Currency;
import java.util.UUID;

@Repository
class JdbcReconciliationCorrectionPort implements ReconciliationCorrectionPort {

    private final JdbcTemplate jdbc;
    private final Clock clock;

    JdbcReconciliationCorrectionPort(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public SettlementCorrectionEligibility lockAndCheck(
            UUID tenantId,
            UUID discrepancyId,
            UUID settlementPostingId,
            UUID originalLedgerTransactionId
    ) {
        UUID batchFamilyId = jdbc.query(
                """
                        SELECT run.batch_family_id
                          FROM reconciliation.settlement_posting_instructions instruction
                          JOIN reconciliation.reconciliation_runs run
                            ON run.run_id = instruction.run_id
                         WHERE instruction.tenant_id = ?
                           AND instruction.settlement_posting_id = ?
                        """,
                (rs, row) -> rs.getObject("batch_family_id", UUID.class),
                tenantId,
                settlementPostingId
        ).stream().findFirst().orElseThrow(() -> new ReconciliationCorrectionException(
                ReconciliationCorrectionError.TARGET_NOT_FOUND,
                "Settlement posting instruction does not exist for the tenant"
        ));

        lockBatchFamily(tenantId, batchFamilyId);

        return jdbc.query(
                """
                        SELECT instruction.tenant_id,
                               run.batch_family_id,
                               instruction.run_id AS source_run_id,
                               current_run.run_id AS current_run_id,
                               candidate.run_id AS invalidating_run_id,
                               candidate.result_id AS discrepancy_id,
                               instruction.settlement_posting_id,
                               application.ledger_transaction_id,
                               instruction.canonical_record_version_id,
                               instruction.subject_type,
                               instruction.subject_id,
                               instruction.amount,
                               instruction.currency
                          FROM reconciliation.settlement_posting_instructions instruction
                          JOIN reconciliation.settlement_posting_applications application
                            ON application.settlement_posting_id = instruction.settlement_posting_id
                          JOIN reconciliation.reconciliation_runs run
                            ON run.run_id = instruction.run_id
                          JOIN reconciliation.current_reconciliation_runs current_run
                            ON current_run.tenant_id = instruction.tenant_id
                           AND current_run.batch_family_id = run.batch_family_id
                          JOIN reconciliation.reconciliation_results candidate
                            ON candidate.result_id = ?
                           AND candidate.tenant_id = instruction.tenant_id
                           AND candidate.result_status = 'DISCREPANCY'
                           AND candidate.canonical_record_version_id
                               = instruction.canonical_record_version_id
                           AND candidate.subject_type = instruction.subject_type
                           AND candidate.subject_id = instruction.subject_id
                          JOIN reconciliation.reconciliation_runs candidate_run
                            ON candidate_run.run_id = candidate.run_id
                           AND candidate_run.tenant_id = instruction.tenant_id
                           AND candidate_run.batch_family_id = run.batch_family_id
                         WHERE instruction.tenant_id = ?
                           AND instruction.settlement_posting_id = ?
                           AND application.status = 'POSTED'
                           AND application.ledger_transaction_id = ?
                           AND instruction.run_id = current_run.run_id
                           AND candidate.run_id <> current_run.run_id
                           AND candidate_run.status IN ('COMPLETED', 'COMPLETED_WITH_DISCREPANCIES')
                         FOR UPDATE OF current_run, instruction, application
                        """,
                (rs, row) -> new SettlementCorrectionEligibility(
                        rs.getObject("tenant_id", UUID.class),
                        rs.getObject("batch_family_id", UUID.class),
                        rs.getObject("current_run_id", UUID.class),
                        rs.getObject("invalidating_run_id", UUID.class),
                        rs.getObject("discrepancy_id", UUID.class),
                        rs.getObject("settlement_posting_id", UUID.class),
                        rs.getObject("ledger_transaction_id", UUID.class),
                        rs.getObject("canonical_record_version_id", UUID.class),
                        rs.getString("subject_type"),
                        rs.getObject("subject_id", UUID.class),
                        rs.getBigDecimal("amount"),
                        Currency.getInstance(rs.getString("currency")),
                        clock.instant()
                ),
                discrepancyId,
                tenantId,
                settlementPostingId,
                originalLedgerTransactionId
        ).stream().findFirst().orElseThrow(() -> new ReconciliationCorrectionException(
                ReconciliationCorrectionError.DISCREPANCY_NOT_ELIGIBLE,
                "The settlement posting is not an eligible invalidated discrepancy"
        ));
    }

    private void lockBatchFamily(UUID tenantId, UUID batchFamilyId) {
        jdbc.queryForObject(
                """
                        SELECT batch_family_id
                          FROM reconciliation.batch_family_controls
                         WHERE tenant_id = ? AND batch_family_id = ?
                         FOR UPDATE
                        """,
                UUID.class,
                tenantId,
                batchFamilyId
        );
    }
}
