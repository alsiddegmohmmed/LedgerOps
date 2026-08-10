package com.ledgerops.casework.infrastructure;

import com.ledgerops.casework.application.CaseStore;
import com.ledgerops.casework.domain.CaseFile;
import com.ledgerops.casework.domain.CaseHistoryEntry;
import com.ledgerops.casework.domain.CaseNote;
import com.ledgerops.casework.domain.CaseResolution;
import com.ledgerops.casework.domain.CaseSeverity;
import com.ledgerops.casework.domain.CaseSourceCategory;
import com.ledgerops.casework.domain.CaseStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class CaseJdbcStore implements CaseStore {
    private static final String SELECT_COLUMNS = """
            id, tenant_id, source_category, source_id, related_payment_id, severity,
            due_at, status, owner_id, resolution, resolution_note,
            corrective_action_required, corrective_action_completed, created_at, updated_at
            """;
    private static final String FIND_SQL = "SELECT " + SELECT_COLUMNS
            + " FROM casework.cases WHERE tenant_id = ? AND id = ?";
    private static final String LOCK_SQL = FIND_SQL + " FOR UPDATE";
    private static final String FIND_BY_SOURCE_SQL = "SELECT " + SELECT_COLUMNS
            + " FROM casework.cases WHERE tenant_id = ? AND source_category = ? AND source_id = ?";
    private static final String QUEUE_SQL = "SELECT " + SELECT_COLUMNS
            + " FROM casework.cases WHERE tenant_id = ? AND status <> 'CLOSED'"
            + " ORDER BY due_at ASC, severity DESC, id ASC";

    private final JdbcTemplate jdbc;

    CaseJdbcStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public CaseFile insertIfAbsent(CaseFile candidate) {
        int inserted = jdbc.update("""
                INSERT INTO casework.cases
                    (id, tenant_id, source_category, source_id, related_payment_id, severity,
                     due_at, status, owner_id, resolution, resolution_note,
                     corrective_action_required, corrective_action_completed, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, source_category, source_id) DO NOTHING
                """, candidate.caseId(), candidate.tenantId(), candidate.sourceCategory().name(),
                candidate.sourceId(), candidate.relatedPaymentId(), candidate.severity().name(),
                Timestamp.from(candidate.dueAt()), candidate.status().name(), candidate.ownerId(),
                nullableName(candidate.resolution()), candidate.resolutionNote(),
                candidate.correctiveActionRequired(), candidate.correctiveActionCompleted(),
                Timestamp.from(candidate.createdAt()), Timestamp.from(candidate.createdAt()));
        if (inserted == 0) {
            return jdbc.query(FIND_BY_SOURCE_SQL, this::mapCase, candidate.tenantId(),
                    candidate.sourceCategory().name(), candidate.sourceId()).stream().findFirst()
                    .orElseThrow(() -> new IllegalStateException("Case conflict was not visible"));
        }
        persistNewEvidence(candidate);
        return candidate;
    }

    @Override
    public Optional<CaseFile> findByTenantAndId(UUID tenantId, UUID caseId) {
        return jdbc.query(FIND_SQL, this::mapCase, tenantId, caseId).stream().findFirst();
    }

    @Override
    public Optional<CaseFile> lockByTenantAndId(UUID tenantId, UUID caseId) {
        return jdbc.query(LOCK_SQL, this::mapCase, tenantId, caseId).stream().findFirst();
    }

    @Override
    public List<CaseFile> queue(UUID tenantId) {
        return jdbc.query(QUEUE_SQL, this::mapCase, tenantId);
    }

    @Override
    public void save(CaseFile file) {
        int updated = jdbc.update("""
                UPDATE casework.cases
                   SET status = ?, owner_id = ?, resolution = ?, resolution_note = ?,
                       corrective_action_required = ?, corrective_action_completed = ?, updated_at = ?
                 WHERE tenant_id = ? AND id = ?
                """, file.status().name(), file.ownerId(), nullableName(file.resolution()),
                file.resolutionNote(), file.correctiveActionRequired(),
                file.correctiveActionCompleted(), Timestamp.from(file.history().isEmpty()
                        ? file.dueAt() : file.history().getLast().occurredAt()),
                file.tenantId(), file.caseId());
        if (updated != 1) throw new IllegalStateException("Case was not updated");
        persistNewEvidence(file);
    }

    private void persistNewEvidence(CaseFile file) {
        for (CaseHistoryEntry entry : file.history()) {
            jdbc.update("""
                    INSERT INTO casework.case_history
                        (case_id, tenant_id, sequence, event_type, from_status, to_status,
                         actor_id, reason, occurred_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (case_id, sequence) DO NOTHING
                    """, file.caseId(), file.tenantId(), entry.sequence(), entry.eventType(),
                    entry.fromStatus() == null ? null : entry.fromStatus().name(),
                    entry.toStatus() == null ? null : entry.toStatus().name(), entry.actorId(),
                    entry.reason(), Timestamp.from(entry.occurredAt()));
        }
        for (CaseNote note : file.notes()) {
            jdbc.update("""
                    INSERT INTO casework.case_notes
                        (id, tenant_id, case_id, author_id, note, created_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT (id) DO NOTHING
                    """, note.noteId(), file.tenantId(), file.caseId(), note.authorId(),
                    note.text(), Timestamp.from(note.createdAt()));
        }
    }

    private CaseFile mapCase(ResultSet rs, int row) throws SQLException {
        UUID caseId = rs.getObject("id", UUID.class);
        UUID tenantId = rs.getObject("tenant_id", UUID.class);
        List<CaseHistoryEntry> history = jdbc.query("""
                SELECT sequence, event_type, from_status, to_status, actor_id, reason, occurred_at
                  FROM casework.case_history WHERE tenant_id = ? AND case_id = ? ORDER BY sequence
                """, (historyRs, ignored) -> new CaseHistoryEntry(
                        historyRs.getLong("sequence"), historyRs.getString("event_type"),
                        nullableEnum(CaseStatus.class, historyRs.getString("from_status")),
                        nullableEnum(CaseStatus.class, historyRs.getString("to_status")),
                        historyRs.getObject("actor_id", UUID.class), historyRs.getString("reason"),
                        historyRs.getTimestamp("occurred_at").toInstant()), tenantId, caseId);
        List<CaseNote> notes = jdbc.query("""
                SELECT id, author_id, note, created_at FROM casework.case_notes
                 WHERE tenant_id = ? AND case_id = ? ORDER BY created_at, id
                """, (noteRs, ignored) -> new CaseNote(
                        noteRs.getObject("id", UUID.class), noteRs.getObject("author_id", UUID.class),
                        noteRs.getString("note"), noteRs.getTimestamp("created_at").toInstant()),
                tenantId, caseId);
        return CaseFile.restore(caseId, tenantId,
                CaseSourceCategory.valueOf(rs.getString("source_category")),
                rs.getObject("source_id", UUID.class),
                CaseSeverity.valueOf(rs.getString("severity")),
                rs.getTimestamp("due_at").toInstant(),
                rs.getTimestamp("created_at").toInstant(),
                rs.getObject("related_payment_id", UUID.class),
                CaseStatus.valueOf(rs.getString("status")),
                rs.getObject("owner_id", UUID.class),
                nullableEnum(CaseResolution.class, rs.getString("resolution")),
                rs.getString("resolution_note"), rs.getBoolean("corrective_action_required"),
                rs.getBoolean("corrective_action_completed"), history, notes);
    }

    private static String nullableName(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private static <T extends Enum<T>> T nullableEnum(Class<T> type, String value) {
        return value == null ? null : Enum.valueOf(type, value);
    }
}
