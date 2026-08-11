package com.ledgerops.reconciliation.infrastructure;

import com.ledgerops.reconciliation.api.SettlementBatchSnapshot;
import com.ledgerops.reconciliation.api.SettlementValidationItemSnapshot;
import com.ledgerops.reconciliation.application.SettlementBatchStore;
import com.ledgerops.reconciliation.domain.SettlementBatchIdentity;
import com.ledgerops.reconciliation.domain.SettlementBatchStatus;
import com.ledgerops.reconciliation.domain.SettlementValidationReasonCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
class JdbcSettlementBatchStore implements SettlementBatchStore {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final JdbcTemplate jdbc;
    private final Clock clock;

    JdbcSettlementBatchStore(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    @Transactional
    public SettlementBatchSnapshot insertReceived(
            UUID batchVersionId,
            SettlementBatchIdentity identity,
            String rawFileSha256,
            String objectKey,
            long byteSize,
            UUID supersedesBatchVersionId,
            UUID createdByApplicationUserId,
            Instant now
    ) {
        UUID familyId = jdbc.query(
                """
                INSERT INTO reconciliation.settlement_batch_families
                    (family_id, tenant_id, provider_id, provider_batch_reference,
                     settlement_period_start, settlement_period_end, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, provider_id, provider_batch_reference,
                             settlement_period_start, settlement_period_end)
                DO UPDATE SET provider_batch_reference = EXCLUDED.provider_batch_reference
                RETURNING family_id
                """,
                rs -> rs.next() ? rs.getObject(1, UUID.class) : null,
                UUID.randomUUID(), identity.tenantId(), identity.providerId(),
                identity.providerBatchReference(), identity.settlementPeriodStart(),
                identity.settlementPeriodEnd(), Timestamp.from(now));
        if (familyId == null) throw new IllegalStateException("Settlement batch family was not created");

        if (supersedesBatchVersionId != null) {
            Integer sameFamily = jdbc.queryForObject("""
                    SELECT count(*)
                      FROM reconciliation.settlement_batch_versions
                     WHERE family_id = ? AND batch_version_id = ?
                    """, Integer.class, familyId, supersedesBatchVersionId);
            if (sameFamily == null || sameFamily != 1) {
                throw new IllegalArgumentException(
                        "A corrected settlement version must supersede a version in the same batch family");
            }
        }

        int inserted = jdbc.update(
                """
                INSERT INTO reconciliation.settlement_batch_versions
                    (batch_version_id, family_id, raw_file_sha256, object_key, byte_size,
                     status, supersedes_batch_version_id, created_by_application_user_id,
                     created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'RECEIVED', ?, ?, ?, ?)
                ON CONFLICT (family_id, raw_file_sha256) DO NOTHING
                """,
                batchVersionId, familyId, rawFileSha256, objectKey, byteSize,
                supersedesBatchVersionId, createdByApplicationUserId,
                Timestamp.from(now), Timestamp.from(now));
        if (inserted == 0) {
            return findByHash(identity.tenantId(), familyId, rawFileSha256)
                    .orElseThrow(() -> new IllegalStateException("Settlement duplicate disappeared"));
        }
        return findById(identity.tenantId(), batchVersionId).orElseThrow();
    }

    @Override
    public Optional<SettlementBatchSnapshot> findById(UUID tenantId, UUID batchVersionId) {
        return jdbc.query(
                """
                SELECT v.batch_version_id, v.family_id, f.tenant_id, f.provider_id,
                       f.provider_batch_reference, f.settlement_period_start,
                       f.settlement_period_end, v.raw_file_sha256, v.object_key, v.byte_size,
                       v.status, v.supersedes_batch_version_id, v.total_rows, v.valid_rows,
                       v.invalid_rows, v.structural_error_code, v.created_at, v.updated_at
                  FROM reconciliation.settlement_batch_versions v
                  JOIN reconciliation.settlement_batch_families f ON f.family_id = v.family_id
                 WHERE f.tenant_id = ? AND v.batch_version_id = ?
                """,
                (rs, row) -> mapSnapshot(rs), tenantId, batchVersionId).stream().findFirst();
    }

    @Override
    public List<SettlementBatchSnapshot> findByTenant(UUID tenantId) {
        return jdbc.query(
                """
                SELECT v.batch_version_id, v.family_id, f.tenant_id, f.provider_id,
                       f.provider_batch_reference, f.settlement_period_start,
                       f.settlement_period_end, v.raw_file_sha256, v.object_key, v.byte_size,
                       v.status, v.supersedes_batch_version_id, v.total_rows, v.valid_rows,
                       v.invalid_rows, v.structural_error_code, v.created_at, v.updated_at
                  FROM reconciliation.settlement_batch_versions v
                  JOIN reconciliation.settlement_batch_families f ON f.family_id = v.family_id
                 WHERE f.tenant_id = ?
                 ORDER BY v.created_at DESC, v.batch_version_id DESC
                """,
                (rs, row) -> mapSnapshot(rs), tenantId);
    }

    @Override
    public List<SettlementValidationItemSnapshot> validationItems(UUID tenantId, UUID batchVersionId) {
        return jdbc.query(
                """
                SELECT i.validation_item_id, i.row_number, i.reason_code,
                       i.safe_evidence::text, i.created_at
                  FROM reconciliation.settlement_validation_items i
                  JOIN reconciliation.settlement_batch_versions v
                    ON v.batch_version_id = i.batch_version_id
                  JOIN reconciliation.settlement_batch_families f ON f.family_id = v.family_id
                 WHERE f.tenant_id = ? AND i.batch_version_id = ?
                 ORDER BY i.row_number, i.validation_item_id
                """,
                (rs, row) -> new SettlementValidationItemSnapshot(
                        rs.getObject("validation_item_id", UUID.class),
                        rs.getLong("row_number"),
                        rs.getString("reason_code"),
                        readMap(rs.getString("safe_evidence")),
                        rs.getTimestamp("created_at").toInstant()),
                tenantId, batchVersionId);
    }

    @Override
    @Transactional
    public void startValidation(UUID tenantId, UUID batchVersionId, Instant now) {
        int updated = jdbc.update(
                """
                UPDATE reconciliation.settlement_batch_versions v
                   SET status = 'VALIDATING', structural_error_code = NULL,
                       total_rows = 0, valid_rows = 0, invalid_rows = 0,
                       updated_at = ?
                 WHERE v.batch_version_id = ?
                   AND v.family_id IN (
                       SELECT family_id FROM reconciliation.settlement_batch_families
                        WHERE tenant_id = ?)
                   AND v.status IN ('RECEIVED', 'FAILED')
                """, Timestamp.from(now), batchVersionId, tenantId);
        if (updated != 1) throw new IllegalStateException("Settlement batch is not ready for validation");
    }

    @Override
    @Transactional
    public void clearValidation(UUID tenantId, UUID batchVersionId) {
        assertTenant(tenantId, batchVersionId);
        jdbc.update("DELETE FROM reconciliation.settlement_validation_items WHERE batch_version_id = ?",
                batchVersionId);
        jdbc.update("DELETE FROM reconciliation.settlement_record_occurrences WHERE batch_version_id = ?",
                batchVersionId);
    }

    @Override
    @Transactional
    public void persistValidationChunk(
            UUID tenantId,
            UUID batchVersionId,
            List<OccurrenceDraft> occurrences,
            List<ValidationItemDraft> validationItems
    ) {
        assertTenant(tenantId, batchVersionId);
        jdbc.batchUpdate(
                """
                INSERT INTO reconciliation.settlement_record_occurrences
                    (occurrence_id, batch_version_id, tenant_id, row_number,
                     provider_record_key, normalized_content_hash, normalized_content,
                     validation_state, reason_code, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)
                """,
                occurrences,
                occurrences.size(),
                (ps, draft) -> {
                    ps.setObject(1, draft.occurrenceId());
                    ps.setObject(2, batchVersionId);
                    ps.setObject(3, tenantId);
                    ps.setLong(4, draft.rowNumber());
                    ps.setString(5, draft.providerRecordKey());
                    ps.setString(6, draft.normalizedContentHash());
                    ps.setString(7, draft.normalizedContent());
                    ps.setString(8, draft.validationState());
                    ps.setString(9, draft.reasonCode());
                    ps.setTimestamp(10, Timestamp.from(clock.instant()));
                });
        insertValidationItems(batchVersionId, validationItems);
    }

    @Override
    @Transactional
    public void quarantineOccurrence(
            UUID tenantId,
            UUID batchVersionId,
            long rowNumber,
            SettlementValidationReasonCode reasonCode,
            ValidationItemDraft validationItem
    ) {
        assertTenant(tenantId, batchVersionId);
        jdbc.update("""
                UPDATE reconciliation.settlement_record_occurrences
                   SET validation_state = 'QUARANTINED', reason_code = ?
                 WHERE batch_version_id = ? AND row_number = ?
                """, reasonCode.name(), batchVersionId, rowNumber);
        insertValidationItems(batchVersionId, List.of(validationItem));
    }

    @Override
    @Transactional
    public void finishValidation(
            UUID tenantId,
            UUID batchVersionId,
            long totalRows,
            long validRows,
            long invalidRows,
            Instant now
    ) {
        int updated = jdbc.update("""
                UPDATE reconciliation.settlement_batch_versions v
                   SET status = 'READY', total_rows = ?, valid_rows = ?, invalid_rows = ?,
                       updated_at = ?
                 WHERE v.batch_version_id = ?
                   AND v.family_id IN (
                       SELECT family_id FROM reconciliation.settlement_batch_families
                        WHERE tenant_id = ?)
                   AND v.status = 'VALIDATING'
                """, totalRows, validRows, invalidRows, Timestamp.from(now), batchVersionId, tenantId);
        if (updated != 1) throw new IllegalStateException("Settlement batch was not validating");
    }

    @Override
    @Transactional
    public void failValidation(UUID tenantId, UUID batchVersionId,
                               SettlementValidationReasonCode reasonCode, Instant now) {
        assertTenant(tenantId, batchVersionId);
        jdbc.update("DELETE FROM reconciliation.settlement_validation_items WHERE batch_version_id = ?",
                batchVersionId);
        jdbc.update("DELETE FROM reconciliation.settlement_record_occurrences WHERE batch_version_id = ?",
                batchVersionId);
        jdbc.update("""
                UPDATE reconciliation.settlement_batch_versions
                   SET status = 'FAILED', structural_error_code = ?,
                       total_rows = 0, valid_rows = 0, invalid_rows = 0, updated_at = ?
                 WHERE batch_version_id = ?
                """, reasonCode.name(), Timestamp.from(now), batchVersionId);
    }

    @Override
    @Transactional
    public void startProcessing(UUID tenantId, UUID batchVersionId, Instant now) {
        int updated = jdbc.update("""
                UPDATE reconciliation.settlement_batch_versions v
                   SET status = 'PROCESSING', updated_at = ?
                 WHERE v.batch_version_id = ?
                   AND v.family_id IN (
                       SELECT family_id FROM reconciliation.settlement_batch_families
                        WHERE tenant_id = ?)
                   AND v.status = 'READY'
                """, Timestamp.from(now), batchVersionId, tenantId);
        if (updated != 1) throw new IllegalStateException("Only READY settlement batches can be processed");
    }

    @Override
    @Transactional
    public void finishProcessing(UUID tenantId, UUID batchVersionId, Instant now) {
        long invalid = jdbc.queryForObject("""
                SELECT invalid_rows FROM reconciliation.settlement_batch_versions v
                 WHERE v.batch_version_id = ? AND v.family_id IN (
                     SELECT family_id FROM reconciliation.settlement_batch_families WHERE tenant_id = ?)
                """, Long.class, batchVersionId, tenantId);
        jdbc.update("""
                UPDATE reconciliation.settlement_batch_versions
                   SET status = ?, updated_at = ?
                 WHERE batch_version_id = ?
                   AND family_id IN (
                       SELECT family_id FROM reconciliation.settlement_batch_families
                        WHERE tenant_id = ?)
                   AND status = 'PROCESSING'
                """, invalid > 0 ? SettlementBatchStatus.COMPLETED_WITH_DISCREPANCIES.name()
                        : SettlementBatchStatus.COMPLETED.name(), Timestamp.from(now), batchVersionId, tenantId);
    }

    @Override
    @Transactional
    public void failProcessing(UUID tenantId, UUID batchVersionId, Instant now) {
        jdbc.update("""
                UPDATE reconciliation.settlement_batch_versions v
                   SET status = 'FAILED', updated_at = ?
                 WHERE v.batch_version_id = ?
                   AND v.family_id IN (
                       SELECT family_id FROM reconciliation.settlement_batch_families
                        WHERE tenant_id = ?)
                   AND v.status = 'PROCESSING'
                """, Timestamp.from(now), batchVersionId, tenantId);
    }

    @Override
    public List<SettlementOccurrenceRow> readValidOccurrences(UUID batchVersionId, int page, int pageSize) {
        return jdbc.query("""
                SELECT occurrence_id, batch_version_id, tenant_id, row_number,
                       provider_record_key, normalized_content_hash, normalized_content::text
                  FROM reconciliation.settlement_record_occurrences
                 WHERE batch_version_id = ? AND validation_state = 'VALID'
                 ORDER BY row_number
                 LIMIT ? OFFSET ?
                """, (rs, row) -> new SettlementOccurrenceRow(
                        rs.getObject("occurrence_id", UUID.class),
                        rs.getObject("batch_version_id", UUID.class),
                        rs.getObject("tenant_id", UUID.class),
                        rs.getLong("row_number"),
                        rs.getString("provider_record_key"),
                        rs.getString("normalized_content_hash"),
                        rs.getString("normalized_content")),
                batchVersionId, pageSize, page * pageSize);
    }

    @Override
    @Transactional
    public void persistCanonicalChunk(UUID batchVersionId, List<SettlementOccurrenceRow> rows, Instant now) {
        for (SettlementOccurrenceRow row : rows) {
            UUID candidateId = UUID.randomUUID();
            int inserted = jdbc.update("""
                    INSERT INTO reconciliation.canonical_settlement_record_versions
                        (canonical_record_version_id, tenant_id, provider_id,
                         provider_record_key, normalized_content_hash,
                         normalized_content, created_at)
                    VALUES (?, ?, 'SIMULATOR', ?, ?, ?::jsonb, ?)
                    ON CONFLICT (tenant_id, provider_id, provider_record_key, normalized_content_hash)
                    DO NOTHING
                    """, candidateId, row.tenantId(), row.providerRecordKey(),
                    row.normalizedContentHash(), row.normalizedContent(), Timestamp.from(now));
            UUID canonicalId = inserted == 1 ? candidateId : jdbc.queryForObject("""
                    SELECT canonical_record_version_id
                      FROM reconciliation.canonical_settlement_record_versions
                     WHERE tenant_id = ? AND provider_id = 'SIMULATOR'
                       AND provider_record_key = ? AND normalized_content_hash = ?
                    """, UUID.class, row.tenantId(), row.providerRecordKey(),
                    row.normalizedContentHash());
            jdbc.update("""
                    UPDATE reconciliation.settlement_record_occurrences
                       SET canonical_record_version_id = ?
                     WHERE occurrence_id = ? AND batch_version_id = ?
                    """, canonicalId, row.occurrenceId(), batchVersionId);
        }
    }

    private Optional<SettlementBatchSnapshot> findByHash(UUID tenantId, UUID familyId, String sha256) {
        return jdbc.query("""
                SELECT v.batch_version_id, v.family_id, f.tenant_id, f.provider_id,
                       f.provider_batch_reference, f.settlement_period_start,
                       f.settlement_period_end, v.raw_file_sha256, v.object_key, v.byte_size,
                       v.status, v.supersedes_batch_version_id, v.total_rows, v.valid_rows,
                       v.invalid_rows, v.structural_error_code, v.created_at, v.updated_at
                  FROM reconciliation.settlement_batch_versions v
                  JOIN reconciliation.settlement_batch_families f ON f.family_id = v.family_id
                 WHERE f.tenant_id = ? AND v.family_id = ? AND v.raw_file_sha256 = ?
                """, (rs, row) -> mapSnapshot(rs), tenantId, familyId, sha256).stream().findFirst();
    }

    private void insertValidationItems(UUID batchVersionId, List<ValidationItemDraft> items) {
        if (items.isEmpty()) return;
        jdbc.batchUpdate("""
                INSERT INTO reconciliation.settlement_validation_items
                    (validation_item_id, batch_version_id, row_number, reason_code,
                     safe_evidence, created_at)
                VALUES (?, ?, ?, ?, ?::jsonb, ?)
                ON CONFLICT (batch_version_id, row_number, reason_code) DO NOTHING
                """, items, items.size(), (ps, item) -> {
                    ps.setObject(1, item.validationItemId());
                    ps.setObject(2, batchVersionId);
                    ps.setLong(3, item.rowNumber());
                    ps.setString(4, item.reasonCode());
                    ps.setString(5, item.safeEvidence());
                    ps.setTimestamp(6, Timestamp.from(item.createdAt()));
                });
    }

    private void assertTenant(UUID tenantId, UUID batchVersionId) {
        if (findById(tenantId, batchVersionId).isEmpty()) {
            throw new IllegalArgumentException("Settlement batch does not exist");
        }
    }

    private SettlementBatchSnapshot mapSnapshot(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new SettlementBatchSnapshot(
                rs.getObject("batch_version_id", UUID.class),
                rs.getObject("family_id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getString("provider_id"),
                rs.getString("provider_batch_reference"),
                rs.getDate("settlement_period_start").toLocalDate(),
                rs.getDate("settlement_period_end").toLocalDate(),
                rs.getString("raw_file_sha256"),
                rs.getString("object_key"),
                rs.getLong("byte_size"),
                SettlementBatchStatus.valueOf(rs.getString("status")),
                rs.getObject("supersedes_batch_version_id", UUID.class),
                rs.getLong("total_rows"),
                rs.getLong("valid_rows"),
                rs.getLong("invalid_rows"),
                rs.getString("structural_error_code"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> readMap(String value) {
        try {
            return JSON.readValue(value, Map.class);
        } catch (Exception exception) {
            throw new IllegalStateException("Settlement validation evidence is not valid JSON", exception);
        }
    }
}
