package com.ledgerops.reconciliation.infrastructure;

import com.ledgerops.ledger.api.LedgerSettlementEvidence;
import com.ledgerops.ledger.api.SettlementCorrectionLedger;
import com.ledgerops.payment.api.PaymentReconciliationSubject;
import com.ledgerops.provider.api.ProviderEvidence;
import com.ledgerops.reconciliation.application.ReconciliationResultDraft;
import com.ledgerops.reconciliation.application.ReconciliationLifecycleEventFactory;
import com.ledgerops.reconciliation.application.ReconciliationSnapshotOccurrence;
import com.ledgerops.reconciliation.application.ReconciliationSnapshotStore;
import com.ledgerops.reconciliation.application.ReconciliationSnapshotSubject;
import com.ledgerops.messaging.api.MessageOutbox;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Currency;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
class JdbcReconciliationSnapshotStore implements ReconciliationSnapshotStore {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final JdbcTemplate jdbc;
    private final MessageOutbox outbox;
    private final SettlementCorrectionLedger correctionLedger;

    JdbcReconciliationSnapshotStore(
            JdbcTemplate jdbc,
            MessageOutbox outbox,
            SettlementCorrectionLedger correctionLedger
    ) {
        this.jdbc = jdbc;
        this.outbox = outbox;
        this.correctionLedger = correctionLedger;
    }

    @Override
    @Transactional
    public SnapshotIdentity createBuildingSnapshot(
            UUID tenantId,
            UUID batchFamilyId,
            UUID batchVersionId,
            String rulesVersion,
            Instant sourceCutoff,
            Instant now
    ) {
        jdbc.queryForObject("""
                SELECT batch_family_id
                  FROM reconciliation.batch_family_controls
                 WHERE tenant_id = ? AND batch_family_id = ?
                 FOR UPDATE
                """, UUID.class, tenantId, batchFamilyId);
        Integer nextRun = jdbc.queryForObject("""
                SELECT COALESCE(MAX(run_number), 0) + 1
                  FROM reconciliation.reconciliation_snapshots
                 WHERE tenant_id = ? AND batch_family_id = ?
                """, Integer.class, tenantId, batchFamilyId);
        if (nextRun == null) {
            throw new IllegalStateException("Could not allocate reconciliation run number");
        }
        UUID snapshotId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO reconciliation.reconciliation_snapshots
                    (snapshot_id, tenant_id, batch_family_id, batch_version_id,
                     run_number, rules_version, source_cutoff, snapshot_status,
                     captured_record_count, captured_fact_count, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'BUILDING', 0, 0, ?)
                """, snapshotId, tenantId, batchFamilyId, batchVersionId, nextRun,
                rulesVersion, Timestamp.from(sourceCutoff), Timestamp.from(now));
        return new SnapshotIdentity(
                snapshotId, tenantId, batchFamilyId, batchVersionId,
                nextRun, rulesVersion, sourceCutoff);
    }

    @Override
    @Transactional
    public void insertSettlementRecords(
            UUID snapshotId,
            UUID tenantId,
            UUID batchVersionId,
            List<ReconciliationSnapshotOccurrence> occurrences,
            Instant capturedAt
    ) {
        if (occurrences.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("""
                INSERT INTO reconciliation.snapshot_settlement_records
                    (snapshot_id, tenant_id, batch_version_id, occurrence_id,
                     canonical_record_version_id, row_number, provider_record_key,
                     normalized_content_hash, normalized_content, validation_state,
                     reason_code, captured_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                ON CONFLICT (snapshot_id, occurrence_id) DO NOTHING
                """, occurrences, occurrences.size(), (ps, row) -> {
            ps.setObject(1, snapshotId);
            ps.setObject(2, tenantId);
            ps.setObject(3, batchVersionId);
            ps.setObject(4, row.occurrenceId());
            ps.setObject(5, row.canonicalRecordVersionId());
            ps.setLong(6, row.rowNumber());
            ps.setString(7, row.providerRecordKey());
            ps.setString(8, row.normalizedContentHash());
            ps.setString(9, row.normalizedContent());
            ps.setString(10, row.validationState());
            ps.setString(11, row.reasonCode());
            ps.setTimestamp(12, Timestamp.from(capturedAt));
        });
    }

    @Override
    @Transactional
    public void insertFinancialSubjects(
            UUID snapshotId,
            UUID tenantId,
            UUID batchVersionId,
            List<ReconciliationSnapshotSubject> subjects,
            Instant capturedAt
    ) {
        if (subjects.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("""
                INSERT INTO reconciliation.snapshot_financial_subjects
                    (snapshot_id, tenant_id, batch_version_id, subject_type, subject_id,
                     payment_id, merchant_id, amount, currency, provider_id,
                     provider_idempotency_key, provider_evidence_id, provider_result_id,
                     provider_reference, provider_result_category, provider_observed_at,
                     financial_status, applied_at, provider_evidence,
                     ledger_transaction_id, ledger_posted_at, ledger_compensates_transaction_id,
                     ledger_total_debits, ledger_total_credits, ledger_entries,
                     ledger_evidence, captured_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb,
                        ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?)
                ON CONFLICT (snapshot_id, subject_type, subject_id) DO NOTHING
                """, subjects, subjects.size(), (ps, row) -> {
            PaymentReconciliationSubject subject = row.subject();
            ProviderEvidence provider = row.providerEvidence();
            LedgerSettlementEvidence ledger = row.ledgerEvidence();
            ps.setObject(1, snapshotId);
            ps.setObject(2, tenantId);
            ps.setObject(3, batchVersionId);
            ps.setString(4, subject.subjectType().name());
            ps.setObject(5, subject.subjectId());
            ps.setObject(6, subject.paymentId());
            ps.setObject(7, subject.merchantId());
            ps.setBigDecimal(8, subject.amount());
            ps.setString(9, subject.currency().getCurrencyCode());
            ps.setString(10, subject.providerId());
            ps.setString(11, subject.providerIdempotencyKey());
            ps.setObject(12, subject.providerEvidenceId());
            ps.setObject(13, subject.providerResultId());
            ps.setString(14, subject.providerReference());
            if (provider == null) ps.setNull(15, java.sql.Types.VARCHAR);
            else ps.setString(15, provider.category().name());
            if (provider == null) ps.setNull(16, java.sql.Types.TIMESTAMP_WITH_TIMEZONE);
            else ps.setTimestamp(16, Timestamp.from(provider.observedAt()));
            ps.setString(17, subject.financialStatus());
            ps.setTimestamp(18, Timestamp.from(subject.appliedAt()));
            ps.setString(19, json(provider));
            if (ledger == null) {
                ps.setNull(20, java.sql.Types.OTHER);
                ps.setNull(21, java.sql.Types.TIMESTAMP_WITH_TIMEZONE);
                ps.setNull(22, java.sql.Types.OTHER);
                ps.setNull(23, java.sql.Types.NUMERIC);
                ps.setNull(24, java.sql.Types.NUMERIC);
                ps.setNull(25, java.sql.Types.OTHER);
            } else {
                ps.setObject(20, ledger.posting().transactionId());
                ps.setTimestamp(21, Timestamp.from(ledger.postedAt()));
                ledger.posting().compensatesTransactionId()
                        .ifPresentOrElse(value -> setObject(ps, 22, value),
                                () -> setNull(ps, 22, java.sql.Types.OTHER));
                ps.setBigDecimal(23, ledger.posting().totalDebits());
                ps.setBigDecimal(24, ledger.posting().totalCredits());
                ps.setString(25, json(ledger.posting().entries()));
            }
            ps.setString(26, json(ledger));
            ps.setTimestamp(27, Timestamp.from(capturedAt));
        });
    }

    @Override
    @Transactional
    public void completeSnapshot(
            UUID snapshotId,
            String snapshotSha256,
            long recordCount,
            long factCount,
            Instant completedAt
    ) {
        int updated = jdbc.update("""
                UPDATE reconciliation.reconciliation_snapshots
                   SET snapshot_status = 'COMPLETE', snapshot_sha256 = ?,
                       captured_record_count = ?, captured_fact_count = ?, completed_at = ?
                 WHERE snapshot_id = ? AND snapshot_status = 'BUILDING'
                """, snapshotSha256, recordCount, factCount,
                Timestamp.from(completedAt), snapshotId);
        if (updated != 1) {
            throw new IllegalStateException("Snapshot is not BUILDING or does not exist");
        }
    }

    @Override
    @Transactional
    public void failSnapshot(UUID snapshotId, String reason, Instant failedAt) {
        int updated = jdbc.update("""
                UPDATE reconciliation.reconciliation_snapshots
                   SET snapshot_status = 'FAILED', failure_reason = ?, completed_at = ?
                 WHERE snapshot_id = ? AND snapshot_status = 'BUILDING'
                """, reason, Timestamp.from(failedAt), snapshotId);
        if (updated != 1) {
            throw new IllegalStateException("Snapshot is not BUILDING or does not exist");
        }
    }

    @Override
    @Transactional
    public UUID createQueuedRun(SnapshotIdentity snapshot, Instant createdAt) {
        UUID runId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO reconciliation.reconciliation_runs
                    (run_id, tenant_id, batch_family_id, batch_version_id, snapshot_id,
                     run_number, rules_version, source_cutoff, status, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'QUEUED', ?)
                """, runId, snapshot.tenantId(), snapshot.batchFamilyId(),
                snapshot.batchVersionId(), snapshot.snapshotId(), snapshot.runNumber(),
                snapshot.rulesVersion(), Timestamp.from(snapshot.sourceCutoff()),
                Timestamp.from(createdAt));
        return runId;
    }

    @Override
    @Transactional
    public void startRun(UUID runId, Instant startedAt) {
        RunIdentity identity = runIdentity(runId);
        int updated = jdbc.update("""
                UPDATE reconciliation.reconciliation_runs
                   SET status = 'RUNNING', started_at = ?
                 WHERE run_id = ? AND status = 'QUEUED'
                """, Timestamp.from(startedAt), runId);
        if (updated != 1) {
            throw new IllegalStateException("Reconciliation run is not QUEUED or does not exist");
        }
        outbox.appendOrGet(ReconciliationLifecycleEventFactory.runChanged(
                identity.tenantId(), identity.batchFamilyId(), runId, "RUN_STARTED", "RUNNING",
                0, 0, 0, startedAt));
    }

    @Override
    @Transactional
    public void seedPendingStatuses(UUID tenantId, UUID runId, UUID snapshotId, Instant occurredAt) {
        jdbc.update("""
                INSERT INTO reconciliation.reconciliation_subject_status_history
                    (status_id, tenant_id, subject_type, subject_id, run_id, status, occurred_at)
                SELECT gen_random_uuid(), tenant_id, subject_type, subject_id, ?, 'PENDING', ?
                  FROM reconciliation.snapshot_financial_subjects
                 WHERE snapshot_id = ? AND tenant_id = ?
                """, runId, Timestamp.from(occurredAt), snapshotId, tenantId);
    }

    @Override
    @Transactional
    public void seedAwaitingBatchStatuses(UUID tenantId, UUID runId, UUID snapshotId, Instant occurredAt) {
        jdbc.update("""
                INSERT INTO reconciliation.reconciliation_subject_status_history
                    (status_id, tenant_id, subject_type, subject_id, run_id, status, occurred_at)
                SELECT gen_random_uuid(), s.tenant_id, s.subject_type, s.subject_id, ?, 'AWAITING_BATCH', ?
                  FROM reconciliation.snapshot_financial_subjects s
                 WHERE s.snapshot_id = ? AND s.tenant_id = ?
                   AND NOT EXISTS (
                       SELECT 1
                         FROM reconciliation.snapshot_settlement_records r
                        WHERE r.snapshot_id = s.snapshot_id
                          AND r.validation_state IN ('VALID', 'QUARANTINED')
                          AND r.normalized_content ->> 'providerIdempotencyKey'
                              = s.provider_idempotency_key
                   )
                """, runId, Timestamp.from(occurredAt), snapshotId, tenantId);
    }

    @Override
    @Transactional
    public void insertResults(
            UUID runId,
            UUID tenantId,
            List<ReconciliationResultDraft> results,
            Instant createdAt
    ) {
        if (results.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("""
                INSERT INTO reconciliation.reconciliation_results
                    (result_id, tenant_id, run_id, snapshot_id, occurrence_id,
                     canonical_record_version_id, subject_type, subject_id,
                     result_status, discrepancy_category, provider_values,
                     internal_values, created_at)
                SELECT ?, ?, r.run_id, r.snapshot_id, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?
                  FROM reconciliation.reconciliation_runs r
                 WHERE r.run_id = ?
                """, results, results.size(), (ps, result) -> {
            ps.setObject(1, result.resultId());
            ps.setObject(2, tenantId);
            ps.setObject(3, result.occurrenceId());
            ps.setObject(4, result.canonicalRecordVersionId());
            ps.setString(5, result.subjectType());
            ps.setObject(6, result.subjectId());
            ps.setString(7, result.resultStatus());
            if (result.discrepancyCategory() == null) ps.setNull(8, java.sql.Types.VARCHAR);
            else ps.setString(8, result.discrepancyCategory().name());
            ps.setString(9, json(result.providerValues()));
            ps.setString(10, json(result.internalValues()));
            ps.setTimestamp(11, Timestamp.from(createdAt));
            ps.setObject(12, runId);
        });
    }

    @Override
    @Transactional
    public void appendSubjectStatuses(
            UUID tenantId,
            UUID runId,
            List<SubjectStatusDraft> statuses,
            Instant occurredAt
    ) {
        if (statuses.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("""
                INSERT INTO reconciliation.reconciliation_subject_status_history
                    (status_id, tenant_id, subject_type, subject_id, run_id, status, occurred_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, statuses, statuses.size(), (ps, status) -> {
            ps.setObject(1, UUID.randomUUID());
            ps.setObject(2, tenantId);
            ps.setString(3, status.subjectType());
            ps.setObject(4, status.subjectId());
            ps.setObject(5, runId);
            ps.setString(6, status.status());
            ps.setTimestamp(7, Timestamp.from(occurredAt));
        });
    }

    @Override
    @Transactional
    public void completeRun(UUID runId, long matched, long unmatched, long discrepancies, Instant completedAt) {
        RunIdentity identity = runIdentity(runId);
        String status = discrepancies > 0 ? "COMPLETED_WITH_DISCREPANCIES" : "COMPLETED";
        int updated = jdbc.update("""
                UPDATE reconciliation.reconciliation_runs
                   SET status = ?, matched_count = ?, unmatched_count = ?,
                       discrepancy_count = ?, terminal_at = ?
                 WHERE run_id = ? AND status = 'RUNNING'
                """, discrepancies > 0 ? "COMPLETED_WITH_DISCREPANCIES" : "COMPLETED",
                matched, unmatched, discrepancies, Timestamp.from(completedAt), runId);
        if (updated != 1) {
            throw new IllegalStateException("Reconciliation run is not RUNNING or does not exist");
        }
        outbox.appendOrGet(ReconciliationLifecycleEventFactory.runChanged(
                identity.tenantId(), identity.batchFamilyId(), runId, "RUN_COMPLETED", status,
                matched, unmatched, discrepancies, completedAt));
    }

    @Override
    @Transactional
    public void failRun(UUID runId, String reason, Instant failedAt) {
        RunIdentity identity = runIdentity(runId);
        int updated = jdbc.update("""
                UPDATE reconciliation.reconciliation_runs
                   SET status = 'FAILED', failure_reason = ?, terminal_at = ?
                 WHERE run_id = ? AND status = 'RUNNING'
                """, reason, Timestamp.from(failedAt), runId);
        if (updated != 1) {
            throw new IllegalStateException("Reconciliation run is not RUNNING or does not exist");
        }
        outbox.appendOrGet(ReconciliationLifecycleEventFactory.runChanged(
                identity.tenantId(), identity.batchFamilyId(), runId, "RUN_FAILED", "FAILED",
                0, 0, 0, failedAt));
    }

    @Override
    @Transactional
    public void promoteCurrentRun(
            UUID tenantId,
            UUID batchFamilyId,
            UUID runId,
            Instant promotedAt
    ) {
        jdbc.queryForObject("""
                SELECT batch_family_id
                  FROM reconciliation.batch_family_controls
                 WHERE tenant_id = ? AND batch_family_id = ?
                 FOR UPDATE
                """, UUID.class, tenantId, batchFamilyId);
        String status = jdbc.queryForObject("""
                SELECT status
                  FROM reconciliation.reconciliation_runs
                 WHERE tenant_id = ? AND batch_family_id = ? AND run_id = ?
                 FOR SHARE
                """, String.class, tenantId, batchFamilyId, runId);
        if (!"COMPLETED".equals(status) && !"COMPLETED_WITH_DISCREPANCIES".equals(status)) {
            throw new IllegalStateException("Only a completed reconciliation run can be promoted");
        }
        UUID currentRunId = jdbc.query("""
                SELECT run_id
                  FROM reconciliation.current_reconciliation_runs
                 WHERE tenant_id = ? AND batch_family_id = ?
                 FOR UPDATE
                """, rs -> rs.next() ? rs.getObject(1, UUID.class) : null,
                tenantId, batchFamilyId);
        if (currentRunId != null && !currentRunId.equals(runId)) {
            List<UUID> invalidated = jdbc.query("""
                    SELECT a.ledger_transaction_id
                      FROM reconciliation.settlement_posting_applications a
                      JOIN reconciliation.settlement_posting_instructions i
                        ON i.settlement_posting_id = a.settlement_posting_id
                     WHERE a.tenant_id = ? AND a.status = 'POSTED' AND i.run_id = ?
                       AND NOT EXISTS (
                           SELECT 1
                             FROM reconciliation.reconciliation_results r
                            WHERE r.run_id = ? AND r.result_status = 'MATCHED'
                              AND r.canonical_record_version_id = i.canonical_record_version_id
                              AND r.subject_type = i.subject_type
                              AND r.subject_id = i.subject_id
                       )
                    """, (rs, row) -> rs.getObject("ledger_transaction_id", UUID.class),
                    tenantId, currentRunId, runId);
            boolean uncompensated = invalidated.stream().anyMatch(transactionId ->
                    correctionLedger.findCompensationForTarget(tenantId, transactionId).isEmpty());
            if (uncompensated) {
                throw new IllegalStateException(
                        "Promotion would invalidate an uncompensated settlement posting");
            }
        }
        jdbc.update("""
                INSERT INTO reconciliation.current_reconciliation_runs
                    (tenant_id, batch_family_id, run_id, promoted_at, updated_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, batch_family_id)
                DO UPDATE SET run_id = EXCLUDED.run_id,
                              promoted_at = EXCLUDED.promoted_at,
                              updated_at = EXCLUDED.updated_at
                """, tenantId, batchFamilyId, runId,
                Timestamp.from(promotedAt), Timestamp.from(promotedAt));
        jdbc.update("""
                INSERT INTO reconciliation.current_reconciliation_subject_status
                    (tenant_id, subject_type, subject_id, run_id, status, occurred_at)
                SELECT tenant_id, subject_type, subject_id, run_id, status, occurred_at
                  FROM (
                       SELECT h.*, ROW_NUMBER() OVER (
                           PARTITION BY subject_type, subject_id
                           ORDER BY occurred_at DESC, status_id DESC) AS position
                         FROM reconciliation.reconciliation_subject_status_history h
                        WHERE h.tenant_id = ? AND h.run_id = ?
                  ) latest
                 WHERE position = 1
                ON CONFLICT (tenant_id, subject_type, subject_id)
                DO UPDATE SET run_id = EXCLUDED.run_id,
                              status = EXCLUDED.status,
                              occurred_at = EXCLUDED.occurred_at
                """, tenantId, runId);
        outbox.appendOrGet(ReconciliationLifecycleEventFactory.currentRunPromoted(
                tenantId, batchFamilyId, runId, promotedAt));
    }

    @Override
    public java.util.Optional<ReconciliationSnapshotStore.CurrentRun> findCurrentRun(
            UUID tenantId, UUID batchFamilyId) {
        return jdbc.query("""
                SELECT tenant_id, batch_family_id, run_id, promoted_at
                  FROM reconciliation.current_reconciliation_runs
                 WHERE tenant_id = ? AND batch_family_id = ?
                """, rs -> rs.next()
                        ? java.util.Optional.of(new ReconciliationSnapshotStore.CurrentRun(
                                rs.getObject("tenant_id", UUID.class),
                                rs.getObject("batch_family_id", UUID.class),
                                rs.getObject("run_id", UUID.class),
                                rs.getTimestamp("promoted_at").toInstant()))
                        : java.util.Optional.empty(), tenantId, batchFamilyId);
    }

    @Override
    public List<ReconciliationSnapshotOccurrence> readOccurrences(UUID snapshotId, int page, int pageSize) {
        return jdbc.query("""
                SELECT snapshot_id, tenant_id, batch_version_id, occurrence_id,
                       canonical_record_version_id, row_number, provider_record_key,
                       normalized_content_hash, normalized_content::text,
                       validation_state, reason_code
                  FROM reconciliation.snapshot_settlement_records
                 WHERE snapshot_id = ?
                 ORDER BY row_number, occurrence_id
                 LIMIT ? OFFSET ?
                """, (rs, row) -> new ReconciliationSnapshotOccurrence(
                rs.getObject("snapshot_id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("batch_version_id", UUID.class),
                rs.getObject("occurrence_id", UUID.class),
                rs.getObject("canonical_record_version_id", UUID.class),
                rs.getLong("row_number"),
                rs.getString("provider_record_key"),
                rs.getString("normalized_content_hash"),
                rs.getString("normalized_content"),
                rs.getString("validation_state"), rs.getString("reason_code")),
                snapshotId, pageSize, page * pageSize);
    }

    @Override
    public Map<SubjectKey, ReconciliationSnapshotSubjectRow> findSubjects(
            UUID snapshotId,
            Collection<SubjectKey> keys
    ) {
        if (keys == null || keys.isEmpty()) {
            return Map.of();
        }
        List<SubjectKey> distinct = keys.stream().distinct().toList();
        String predicates = String.join(" OR ",
                java.util.Collections.nCopies(distinct.size(), "(subject_type = ? AND subject_id = ?)"));
        List<Object> args = new ArrayList<>();
        args.add(snapshotId);
        for (SubjectKey key : distinct) {
            args.add(key.subjectType());
            args.add(key.subjectId());
        }
        List<Map.Entry<SubjectKey, ReconciliationSnapshotSubjectRow>> rows = jdbc.query("""
                SELECT subject_type, subject_id, payment_id, merchant_id, amount, currency,
                       provider_id, provider_idempotency_key, provider_evidence_id,
                       provider_result_id, provider_reference, provider_result_category,
                       provider_observed_at, financial_status, applied_at,
                       ledger_transaction_id, ledger_posted_at,
                       ledger_compensates_transaction_id, ledger_total_debits,
                       ledger_total_credits, ledger_entries::text
                  FROM reconciliation.snapshot_financial_subjects
                 WHERE snapshot_id = ? AND (%s)
                """.formatted(predicates), (rs, row) -> new java.util.AbstractMap.SimpleEntry<>(
                new SubjectKey(rs.getString("subject_type"), rs.getObject("subject_id", UUID.class)),
                new ReconciliationSnapshotSubjectRow(
                        new SubjectKey(rs.getString("subject_type"), rs.getObject("subject_id", UUID.class)),
                        rs.getObject("payment_id", UUID.class),
                        rs.getObject("merchant_id", UUID.class),
                        rs.getBigDecimal("amount"),
                        Currency.getInstance(rs.getString("currency")),
                        rs.getString("provider_id"),
                        rs.getString("provider_idempotency_key"),
                        rs.getObject("provider_evidence_id", UUID.class),
                        rs.getObject("provider_result_id", UUID.class),
                        rs.getString("provider_reference"),
                        rs.getString("provider_result_category"),
                        instant(rs, "provider_observed_at"),
                        rs.getString("financial_status"),
                        rs.getTimestamp("applied_at").toInstant(),
                        rs.getObject("ledger_transaction_id", UUID.class),
                        instant(rs, "ledger_posted_at"),
                        rs.getObject("ledger_compensates_transaction_id", UUID.class),
                        rs.getBigDecimal("ledger_total_debits"),
                        rs.getBigDecimal("ledger_total_credits"),
                        rs.getString("ledger_entries")))
                , args.toArray());
        return rows.stream()
                .collect(java.util.stream.Collectors.toMap(
                        Map.Entry::getKey, Map.Entry::getValue,
                        (first, ignored) -> first, java.util.LinkedHashMap::new));
    }

    @Override
    public Map<String, List<SubjectKey>> findSubjectKeysByProviderReferences(
            UUID snapshotId,
            Collection<String> providerReferences
    ) {
        if (providerReferences == null || providerReferences.isEmpty()) {
            return Map.of();
        }
        List<String> distinct = providerReferences.stream()
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        if (distinct.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", java.util.Collections.nCopies(distinct.size(), "?"));
        List<Map.Entry<String, SubjectKey>> rows = jdbc.query("""
                SELECT provider_reference, subject_type, subject_id
                  FROM reconciliation.snapshot_financial_subjects
                 WHERE snapshot_id = ? AND provider_reference IN (%s)
                 ORDER BY provider_reference, subject_type, subject_id
                """.formatted(placeholders), (rs, row) ->
                new java.util.AbstractMap.SimpleEntry<>(
                        rs.getString("provider_reference"),
                        new SubjectKey(rs.getString("subject_type"),
                                rs.getObject("subject_id", UUID.class))),
                prepend(snapshotId, distinct));
        return rows.stream().collect(java.util.stream.Collectors.groupingBy(
                Map.Entry::getKey,
                java.util.LinkedHashMap::new,
                java.util.stream.Collectors.mapping(Map.Entry::getValue,
                        java.util.stream.Collectors.toList())));
    }

    @Override
    public List<ReconciliationSnapshotSubjectRow> findSubjectsWithoutSettlementRecord(
            UUID snapshotId,
            int page,
            int pageSize
    ) {
        return jdbc.query("""
                SELECT subject_type, subject_id, payment_id, merchant_id, amount, currency,
                       provider_id, provider_idempotency_key, provider_evidence_id,
                       provider_result_id, provider_reference, provider_result_category,
                       provider_observed_at, financial_status, applied_at,
                       ledger_transaction_id, ledger_posted_at,
                       ledger_compensates_transaction_id, ledger_total_debits,
                       ledger_total_credits, ledger_entries::text
                  FROM reconciliation.snapshot_financial_subjects s
                 WHERE s.snapshot_id = ?
                   AND NOT EXISTS (
                       SELECT 1
                         FROM reconciliation.snapshot_settlement_records r
                        WHERE r.snapshot_id = s.snapshot_id
                          AND r.normalized_content ->> 'providerIdempotencyKey'
                              = s.provider_idempotency_key
                   )
                 ORDER BY s.applied_at, s.subject_id, s.subject_type
                 LIMIT ? OFFSET ?
                """, (rs, row) -> subjectRow(rs), snapshotId, pageSize, page * pageSize);
    }

    private ReconciliationSnapshotSubjectRow subjectRow(ResultSet rs) throws SQLException {
        SubjectKey key = new SubjectKey(
                rs.getString("subject_type"), rs.getObject("subject_id", UUID.class));
        return new ReconciliationSnapshotSubjectRow(
                key,
                rs.getObject("payment_id", UUID.class),
                rs.getObject("merchant_id", UUID.class),
                rs.getBigDecimal("amount"),
                Currency.getInstance(rs.getString("currency")),
                rs.getString("provider_id"),
                rs.getString("provider_idempotency_key"),
                rs.getObject("provider_evidence_id", UUID.class),
                rs.getObject("provider_result_id", UUID.class),
                rs.getString("provider_reference"),
                rs.getString("provider_result_category"),
                instant(rs, "provider_observed_at"),
                rs.getString("financial_status"),
                rs.getTimestamp("applied_at").toInstant(),
                rs.getObject("ledger_transaction_id", UUID.class),
                instant(rs, "ledger_posted_at"),
                rs.getObject("ledger_compensates_transaction_id", UUID.class),
                rs.getBigDecimal("ledger_total_debits"),
                rs.getBigDecimal("ledger_total_credits"),
                rs.getString("ledger_entries"));
    }

    private static Object[] prepend(Object first, List<?> rest) {
        Object[] values = new Object[rest.size() + 1];
        values[0] = first;
        for (int index = 0; index < rest.size(); index++) {
            values[index + 1] = rest.get(index);
        }
        return values;
    }

    private String json(Object value) {
        try {
            return value == null ? "{}" : JSON.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Reconciliation evidence could not be encoded", exception);
        }
    }

    private static void setNull(java.sql.PreparedStatement ps, int index, int type) {
        try {
            ps.setNull(index, type);
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not set nullable reconciliation value", exception);
        }
    }

    private static void setObject(java.sql.PreparedStatement ps, int index, Object value) {
        try {
            ps.setObject(index, value);
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not set reconciliation value", exception);
        }
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private RunIdentity runIdentity(UUID runId) {
        return jdbc.queryForObject("""
                SELECT tenant_id, batch_family_id
                  FROM reconciliation.reconciliation_runs
                 WHERE run_id = ?
                """, (rs, row) -> new RunIdentity(
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("batch_family_id", UUID.class)), runId);
    }

    private record RunIdentity(UUID tenantId, UUID batchFamilyId) {
    }
}
