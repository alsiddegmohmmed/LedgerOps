package com.ledgerops.audit.infrastructure;

import com.ledgerops.audit.application.AuditSearchStore;
import com.ledgerops.audit.api.AuditSearchQuery;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Repository
class AuditJdbcSearchStore implements AuditSearchStore {

    private final JdbcTemplate jdbcTemplate;

    AuditJdbcSearchStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Batch findBatch(
            AuditSearchQuery query,
            Instant cursorOccurredAt,
            UUID cursorAuditId,
            int limit
    ) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, actor_issuer, actor_subject, principal_type, tenant_id,
                       action_type, target_type, target_id, correlation_id,
                       reason, details, occurred_at
                  FROM audit.audit_records
                 WHERE tenant_id = ?
                """);
        List<Object> arguments = new ArrayList<>();
        arguments.add(query.tenantId());
        appendLike(sql, arguments, "actor_issuer", query.actorIssuer());
        appendLike(sql, arguments, "actor_subject", query.actorSubject());
        appendLike(sql, arguments, "action_type", query.action());
        appendLike(sql, arguments, "target_type", query.entity());
        appendLike(sql, arguments, "target_id", query.entityId());
        appendLike(sql, arguments, "correlation_id", query.correlationId());
        if (query.result() != null && !query.result().isBlank()) {
            sql.append(" AND (lower(action_type) LIKE lower(?)"
                    + " OR lower(details) LIKE lower(?))");
            String value = "%" + query.result().trim() + "%";
            arguments.add(value);
            arguments.add(value);
        }
        if (query.fromInclusive() != null) {
            sql.append(" AND occurred_at >= ?");
            arguments.add(Timestamp.from(query.fromInclusive()));
        }
        if (query.toExclusive() != null) {
            sql.append(" AND occurred_at < ?");
            arguments.add(Timestamp.from(query.toExclusive()));
        }
        if (cursorOccurredAt != null && cursorAuditId != null) {
            sql.append(" AND (occurred_at < ? OR (occurred_at = ? AND id < ?))");
            arguments.add(Timestamp.from(cursorOccurredAt));
            arguments.add(Timestamp.from(cursorOccurredAt));
            arguments.add(cursorAuditId);
        }
        sql.append(" ORDER BY occurred_at DESC, id DESC LIMIT ?");
        arguments.add(limit + 1);
        List<Row> rows = jdbcTemplate.query(sql.toString(), this::map, arguments.toArray());
        boolean hasMore = rows.size() > limit;
        if (hasMore) {
            rows = rows.subList(0, limit);
        }
        return new Batch(rows, hasMore);
    }

    private Row map(ResultSet rs, int rowNumber) throws SQLException {
        return new Row(
                rs.getObject("id", UUID.class),
                rs.getString("actor_issuer"),
                rs.getString("actor_subject"),
                rs.getString("principal_type"),
                rs.getObject("tenant_id", UUID.class),
                rs.getString("action_type"),
                rs.getString("target_type"),
                rs.getString("target_id"),
                rs.getString("correlation_id"),
                rs.getString("reason"),
                rs.getString("details"),
                rs.getTimestamp("occurred_at").toInstant());
    }

    private static void appendLike(
            StringBuilder sql,
            List<Object> arguments,
            String column,
            String value
    ) {
        if (value != null && !value.isBlank()) {
            sql.append(" AND ").append(column).append(" ILIKE ?");
            arguments.add("%" + value.trim() + "%");
        }
    }
}
