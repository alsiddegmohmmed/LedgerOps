package com.ledgerops.reconciliation.infrastructure;

import com.ledgerops.reconciliation.application.SettlementPostingStore;
import com.ledgerops.reconciliation.application.ReconciliationLifecycleEventFactory;
import com.ledgerops.messaging.api.MessageOutbox;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class JdbcSettlementPostingStore implements SettlementPostingStore {

    private final JdbcTemplate jdbc;
    private final MessageOutbox outbox;

    JdbcSettlementPostingStore(JdbcTemplate jdbc, MessageOutbox outbox) {
        this.jdbc = jdbc;
        this.outbox = outbox;
    }

    @Override
    public List<SettlementPostingCandidate> findEligibleCandidates(UUID tenantId, UUID runId) {
        return jdbc.query("""
                SELECT r.tenant_id, run.batch_family_id, r.run_id, r.snapshot_id,
                       r.canonical_record_version_id, r.occurrence_id, r.subject_type,
                       r.subject_id, s.payment_id, s.amount, s.currency
                  FROM reconciliation.reconciliation_results r
                  JOIN reconciliation.reconciliation_runs run ON run.run_id = r.run_id
                  JOIN reconciliation.snapshot_financial_subjects s
                    ON s.snapshot_id = r.snapshot_id
                   AND s.subject_type = r.subject_type
                   AND s.subject_id = r.subject_id
                 WHERE r.tenant_id = ? AND r.run_id = ? AND r.result_status = 'MATCHED'
                   AND NOT EXISTS (
                       SELECT 1
                         FROM reconciliation.reconciliation_results duplicate
                        WHERE duplicate.run_id = r.run_id
                          AND duplicate.canonical_record_version_id = r.canonical_record_version_id
                          AND duplicate.result_status = 'DISCREPANCY'
                   )
                 ORDER BY CASE WHEN r.subject_type = 'PAYMENT' THEN 0 ELSE 1 END,
                          r.canonical_record_version_id, r.occurrence_id
                """, (rs, row) -> new SettlementPostingCandidate(
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("batch_family_id", UUID.class),
                rs.getObject("run_id", UUID.class),
                rs.getObject("snapshot_id", UUID.class),
                rs.getObject("canonical_record_version_id", UUID.class),
                rs.getObject("occurrence_id", UUID.class),
                rs.getString("subject_type"),
                rs.getObject("subject_id", UUID.class),
                rs.getObject("payment_id", UUID.class),
                rs.getBigDecimal("amount"),
                Currency.getInstance(rs.getString("currency"))), tenantId, runId);
    }

    @Override
    @Transactional
    public PostingWork ensureWork(
            SettlementPostingCandidate candidate,
            String templateVersion,
            String instructionHash,
            Instant createdAt
    ) {
        lockControl(candidate.tenantId(), candidate.batchFamilyId());
        UUID currentRun = jdbc.query("""
                SELECT run_id
                  FROM reconciliation.current_reconciliation_runs
                 WHERE tenant_id = ? AND batch_family_id = ?
                 FOR SHARE
                """, rs -> rs.next() ? rs.getObject(1, UUID.class) : null,
                candidate.tenantId(), candidate.batchFamilyId());
        if (!candidate.runId().equals(currentRun)) {
            throw new IllegalStateException("Settlement posting run is not current");
        }
        UUID postingId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO reconciliation.settlement_posting_instructions
                    (settlement_posting_id, tenant_id, canonical_record_version_id,
                     occurrence_id, subject_type, subject_id, template_version,
                     run_id, amount, currency, instruction_hash, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, canonical_record_version_id, subject_type, subject_id,
                             template_version) DO NOTHING
                """, postingId, candidate.tenantId(), candidate.canonicalRecordVersionId(),
                candidate.occurrenceId(), candidate.subjectType(), candidate.subjectId(),
                templateVersion, candidate.runId(), candidate.amount(),
                candidate.currency().getCurrencyCode(), instructionHash, Timestamp.from(createdAt));
        UUID actualPostingId = jdbc.queryForObject("""
                SELECT settlement_posting_id
                  FROM reconciliation.settlement_posting_instructions
                 WHERE tenant_id = ? AND canonical_record_version_id = ?
                   AND subject_type = ? AND subject_id = ? AND template_version = ?
                """, UUID.class, candidate.tenantId(), candidate.canonicalRecordVersionId(),
                candidate.subjectType(), candidate.subjectId(), templateVersion);
        jdbc.update("""
                INSERT INTO reconciliation.settlement_posting_applications
                    (settlement_posting_id, tenant_id, status, updated_at)
                VALUES (?, ?, 'PENDING', ?)
                ON CONFLICT (settlement_posting_id) DO NOTHING
                """, actualPostingId, candidate.tenantId(), Timestamp.from(createdAt));
        return readWorkByPostingId(candidate.tenantId(), candidate.batchFamilyId(),
                actualPostingId, true).orElseThrow(
                () -> new IllegalStateException("Settlement posting application was not created"));
    }

    @Override
    @Transactional
    public Optional<PostingWork> lockWorkForPosting(
            UUID tenantId,
            UUID batchFamilyId,
            UUID settlementPostingId
    ) {
        lockControl(tenantId, batchFamilyId);
        return readWorkByPostingId(tenantId, batchFamilyId, settlementPostingId, true);
    }

    @Override
    public boolean paymentSettlementIsPosted(UUID tenantId, UUID paymentId, String templateVersion) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM reconciliation.settlement_posting_instructions i
                  JOIN reconciliation.settlement_posting_applications a
                    ON a.settlement_posting_id = i.settlement_posting_id
                 WHERE i.tenant_id = ? AND i.subject_type = 'PAYMENT'
                   AND i.subject_id = ? AND i.template_version = ?
                   AND a.status = 'POSTED'
                """, Integer.class, tenantId, paymentId, templateVersion);
        return count != null && count == 1;
    }

    @Override
    @Transactional
    public void markPosted(UUID tenantId, UUID settlementPostingId, UUID ledgerTransactionId, Instant postedAt) {
        PostingIdentity identity = postingIdentity(tenantId, settlementPostingId);
        int updated = jdbc.update("""
                UPDATE reconciliation.settlement_posting_applications
                   SET status = 'POSTED', ledger_transaction_id = ?, posted_at = ?, updated_at = ?
                 WHERE tenant_id = ? AND settlement_posting_id = ? AND status = 'PENDING'
                """, ledgerTransactionId, Timestamp.from(postedAt), Timestamp.from(postedAt),
                tenantId, settlementPostingId);
        if (updated != 1) {
            throw new IllegalStateException("Settlement posting application is not pending");
        }
        outbox.appendOrGet(ReconciliationLifecycleEventFactory.postingChanged(
                tenantId, identity.batchFamilyId(), identity.runId(), settlementPostingId,
                "POSTING_COMPLETED", "POSTED", identity.subjectType(), identity.subjectId(),
                ledgerTransactionId, null, postedAt));
    }

    @Override
    @Transactional
    public void recordFailure(
            UUID tenantId,
            UUID settlementPostingId,
            String failureCode,
            String safeMessage,
            Instant failedAt
    ) {
        PostingIdentity identity = postingIdentity(tenantId, settlementPostingId);
        Integer next = jdbc.queryForObject("""
                SELECT COALESCE(MAX(attempt_number), 0) + 1
                  FROM reconciliation.settlement_posting_failures
                 WHERE tenant_id = ? AND settlement_posting_id = ?
                """, Integer.class, tenantId, settlementPostingId);
        jdbc.update("""
                INSERT INTO reconciliation.settlement_posting_failures
                    (failure_id, settlement_posting_id, tenant_id, attempt_number,
                     failure_code, safe_message, failed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), settlementPostingId, tenantId, next,
                failureCode, safeMessage, Timestamp.from(failedAt));
        outbox.appendOrGet(ReconciliationLifecycleEventFactory.postingChanged(
                tenantId, identity.batchFamilyId(), identity.runId(), settlementPostingId,
                "POSTING_FAILED", "PENDING", identity.subjectType(), identity.subjectId(),
                null, failureCode, failedAt));
    }

    private void lockControl(UUID tenantId, UUID batchFamilyId) {
        jdbc.queryForObject("""
                SELECT batch_family_id
                  FROM reconciliation.batch_family_controls
                 WHERE tenant_id = ? AND batch_family_id = ?
                 FOR UPDATE
                """, UUID.class, tenantId, batchFamilyId);
    }

    private Optional<PostingWork> readWorkByPostingId(
            UUID tenantId, UUID batchFamilyId, UUID postingId, boolean lock
    ) {
        String lockClause = lock ? " FOR UPDATE OF i, a" : "";
        return jdbc.query("""
                SELECT i.settlement_posting_id, i.tenant_id, run.batch_family_id,
                       i.run_id, i.canonical_record_version_id, i.occurrence_id,
                       i.subject_type, i.subject_id, s.payment_id, i.template_version,
                       i.amount, i.currency, i.instruction_hash, a.status,
                       a.ledger_transaction_id
                  FROM reconciliation.settlement_posting_instructions i
                  JOIN reconciliation.settlement_posting_applications a
                    ON a.settlement_posting_id = i.settlement_posting_id
                  JOIN reconciliation.reconciliation_runs run ON run.run_id = i.run_id
                  JOIN reconciliation.snapshot_financial_subjects s
                    ON s.snapshot_id = run.snapshot_id
                   AND s.subject_type = i.subject_type
                   AND s.subject_id = i.subject_id
                 WHERE i.tenant_id = ? AND run.batch_family_id = ?
                   AND i.settlement_posting_id = ?
                """ + lockClause, (rs, row) -> new PostingWork(
                rs.getObject("settlement_posting_id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("batch_family_id", UUID.class),
                rs.getObject("run_id", UUID.class),
                rs.getObject("canonical_record_version_id", UUID.class),
                rs.getObject("occurrence_id", UUID.class),
                rs.getString("subject_type"),
                rs.getObject("subject_id", UUID.class),
                rs.getObject("payment_id", UUID.class),
                rs.getString("template_version"),
                rs.getBigDecimal("amount"),
                Currency.getInstance(rs.getString("currency")),
                rs.getString("instruction_hash"),
                rs.getString("status"),
                rs.getObject("ledger_transaction_id", UUID.class)),
                tenantId, batchFamilyId, postingId).stream().findFirst();
    }

    private Optional<PostingWork> readWork(
            UUID tenantId,
            UUID batchFamilyId,
            UUID runId,
            UUID canonicalId,
            String subjectType,
            UUID subjectId,
            String templateVersion,
            boolean lock
    ) {
        String lockClause = lock ? " FOR UPDATE OF i, a" : "";
        List<Object> arguments = new ArrayList<>(List.of(
                tenantId, batchFamilyId, runId, canonicalId, subjectType, subjectId, templateVersion));
        return jdbc.query("""
                SELECT i.settlement_posting_id, i.tenant_id, run.batch_family_id,
                       i.run_id, i.canonical_record_version_id, i.occurrence_id,
                       i.subject_type, i.subject_id, s.payment_id, i.template_version,
                       i.amount, i.currency, i.instruction_hash, a.status,
                       a.ledger_transaction_id
                  FROM reconciliation.settlement_posting_instructions i
                  JOIN reconciliation.settlement_posting_applications a
                    ON a.settlement_posting_id = i.settlement_posting_id
                  JOIN reconciliation.reconciliation_runs run ON run.run_id = i.run_id
                  JOIN reconciliation.snapshot_financial_subjects s
                    ON s.snapshot_id = run.snapshot_id
                   AND s.subject_type = i.subject_type
                   AND s.subject_id = i.subject_id
                 WHERE i.tenant_id = ? AND run.batch_family_id = ? AND i.run_id = ?
                   AND i.canonical_record_version_id = ? AND i.subject_type = ?
                   AND i.subject_id = ? AND i.template_version = ?
                """ + lockClause, (rs, row) -> new PostingWork(
                rs.getObject("settlement_posting_id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("batch_family_id", UUID.class),
                rs.getObject("run_id", UUID.class),
                rs.getObject("canonical_record_version_id", UUID.class),
                rs.getObject("occurrence_id", UUID.class),
                rs.getString("subject_type"),
                rs.getObject("subject_id", UUID.class),
                rs.getObject("payment_id", UUID.class),
                rs.getString("template_version"),
                rs.getBigDecimal("amount"),
                Currency.getInstance(rs.getString("currency")),
                rs.getString("instruction_hash"),
                rs.getString("status"),
                rs.getObject("ledger_transaction_id", UUID.class)), arguments.toArray()).stream().findFirst();
    }

    private PostingIdentity postingIdentity(UUID tenantId, UUID settlementPostingId) {
        return jdbc.queryForObject("""
                SELECT run.batch_family_id, i.run_id, i.subject_type, i.subject_id
                  FROM reconciliation.settlement_posting_instructions i
                  JOIN reconciliation.reconciliation_runs run ON run.run_id = i.run_id
                 WHERE i.tenant_id = ? AND i.settlement_posting_id = ?
                """, (rs, row) -> new PostingIdentity(
                rs.getObject("batch_family_id", UUID.class),
                rs.getObject("run_id", UUID.class),
                rs.getString("subject_type"),
                rs.getObject("subject_id", UUID.class)), tenantId, settlementPostingId);
    }

    private record PostingIdentity(
            UUID batchFamilyId,
            UUID runId,
            String subjectType,
            UUID subjectId
    ) {
    }
}
