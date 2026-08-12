package com.ledgerops.casework.infrastructure;

import com.ledgerops.casework.application.CorrectionRequestStore;
import com.ledgerops.casework.domain.CorrectionRequest;
import com.ledgerops.casework.domain.CorrectionRequestKind;
import com.ledgerops.casework.domain.CorrectionRequestStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class CorrectionRequestJdbcStore implements CorrectionRequestStore {

    private static final String COLUMNS = """
            correction_id, tenant_id, case_id, discrepancy_id,
            settlement_posting_id, original_ledger_transaction_id, kind,
            requested_by, reason, requested_at, status, updated_at,
            compensation_ledger_transaction_id, failure_reason
            """;

    private final JdbcTemplate jdbc;

    CorrectionRequestJdbcStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public CorrectionRequest insertIfAbsent(CorrectionRequest candidate) {
        int inserted = jdbc.update(
                """
                        INSERT INTO casework.correction_requests (
                            correction_id, tenant_id, case_id, discrepancy_id,
                            settlement_posting_id, original_ledger_transaction_id,
                            kind, requested_by, reason, requested_at, status,
                            updated_at, compensation_ledger_transaction_id, failure_reason
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON CONFLICT (tenant_id, original_ledger_transaction_id) DO NOTHING
                        """,
                candidate.correctionId(), candidate.tenantId(), candidate.caseId(),
                candidate.discrepancyId(), candidate.settlementPostingInstructionId(),
                candidate.originalLedgerTransactionId(), candidate.kind().name(),
                candidate.requestedBy(), candidate.reason(), Timestamp.from(candidate.requestedAt()),
                candidate.status().name(), Timestamp.from(candidate.updatedAt()),
                candidate.compensationLedgerTransactionId(), candidate.failureReason()
        );
        if (inserted == 1) {
            return candidate;
        }
        return findByTarget(candidate.tenantId(), candidate.originalLedgerTransactionId())
                .orElseThrow(() -> new IllegalStateException(
                        "Correction request conflict was not visible"));
    }

    @Override
    public Optional<CorrectionRequest> findByTenantAndId(UUID tenantId, UUID correctionId) {
        return jdbc.query(
                "SELECT " + COLUMNS
                        + " FROM casework.correction_requests WHERE tenant_id = ? AND correction_id = ?",
                this::map,
                tenantId,
                correctionId
        ).stream().findFirst();
    }

    @Override
    public Optional<CorrectionRequest> lockByTenantAndId(UUID tenantId, UUID correctionId) {
        return jdbc.query(
                "SELECT " + COLUMNS
                        + " FROM casework.correction_requests WHERE tenant_id = ? AND correction_id = ? FOR UPDATE",
                this::map,
                tenantId,
                correctionId
        ).stream().findFirst();
    }

    @Override
    public Optional<CorrectionRequest> findCompletedForCase(
            UUID tenantId,
            UUID caseId,
            UUID discrepancyId
    ) {
        return jdbc.query(
                """
                        SELECT %s
                          FROM casework.correction_requests
                         WHERE tenant_id = ? AND case_id = ? AND discrepancy_id = ?
                           AND status = 'COMPLETED'
                         ORDER BY updated_at DESC, correction_id DESC
                        """.formatted(COLUMNS),
                this::map,
                tenantId,
                caseId,
                discrepancyId
        ).stream().findFirst();
    }

    @Override
    public void save(CorrectionRequest request) {
        int updated = jdbc.update(
                """
                        UPDATE casework.correction_requests
                           SET status = ?, updated_at = ?,
                               compensation_ledger_transaction_id = ?, failure_reason = ?
                         WHERE tenant_id = ? AND correction_id = ?
                        """,
                request.status().name(), Timestamp.from(request.updatedAt()),
                request.compensationLedgerTransactionId(), request.failureReason(),
                request.tenantId(), request.correctionId()
        );
        if (updated != 1) {
            throw new IllegalStateException("Correction request was not updated");
        }
    }

    private Optional<CorrectionRequest> findByTarget(UUID tenantId, UUID originalTransactionId) {
        return jdbc.query(
                "SELECT " + COLUMNS
                        + " FROM casework.correction_requests"
                        + " WHERE tenant_id = ? AND original_ledger_transaction_id = ?",
                this::map,
                tenantId,
                originalTransactionId
        ).stream().findFirst();
    }

    private CorrectionRequest map(ResultSet rs, int ignored) throws SQLException {
        return CorrectionRequest.restore(
                rs.getObject("correction_id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("case_id", UUID.class),
                rs.getObject("discrepancy_id", UUID.class),
                rs.getObject("settlement_posting_id", UUID.class),
                rs.getObject("original_ledger_transaction_id", UUID.class),
                CorrectionRequestKind.valueOf(rs.getString("kind")),
                rs.getObject("requested_by", UUID.class),
                rs.getString("reason"),
                rs.getTimestamp("requested_at").toInstant(),
                CorrectionRequestStatus.valueOf(rs.getString("status")),
                rs.getTimestamp("updated_at").toInstant(),
                rs.getObject("compensation_ledger_transaction_id", UUID.class),
                rs.getString("failure_reason")
        );
    }
}
