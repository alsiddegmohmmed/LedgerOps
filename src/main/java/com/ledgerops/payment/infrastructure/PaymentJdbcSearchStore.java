package com.ledgerops.payment.infrastructure;

import com.ledgerops.payment.application.PaymentSearchStore;
import com.ledgerops.payment.api.PaymentSearchQuery;
import com.ledgerops.payment.domain.PaymentStatus;
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
class PaymentJdbcSearchStore implements PaymentSearchStore {

    private final JdbcTemplate jdbcTemplate;

    PaymentJdbcSearchStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Batch findBatch(
            PaymentSearchQuery query,
            Instant cursorCreatedAt,
            UUID cursorPaymentId,
            int limit
    ) {
        StringBuilder sql = new StringBuilder("""
                SELECT p.id, p.tenant_id, p.merchant_id, p.customer_id,
                       p.amount, p.currency, p.status, p.created_at, p.updated_at
                  FROM payment.payments p
                 WHERE p.tenant_id = ?
                """);
        List<Object> arguments = new ArrayList<>();
        arguments.add(query.tenantId());

        if (!query.authorization().isTenantWide()) {
            sql.append(" AND p.merchant_id IN (");
            appendPlaceholders(sql, query.authorization().merchantIds().size());
            sql.append(")");
            arguments.addAll(query.authorization().merchantIds());
        }
        if (query.paymentId() != null) {
            sql.append(" AND p.id = ?");
            arguments.add(query.paymentId());
        }
        if (query.merchantReference() != null) {
            sql.append(" AND p.merchant_id = ?");
            arguments.add(query.merchantReference());
        }
        if (query.customerId() != null) {
            sql.append(" AND p.customer_id = ?");
            arguments.add(query.customerId());
        }
        if (query.providerId() != null) {
            sql.append(" AND EXISTS (SELECT 1 FROM payment.payment_attempts a"
                    + " WHERE a.tenant_id = p.tenant_id"
                    + " AND a.payment_id = p.id AND a.provider_id = ?)");
            arguments.add(query.providerId());
        }
        if (query.fromInclusive() != null) {
            sql.append(" AND p.created_at >= ?");
            arguments.add(Timestamp.from(query.fromInclusive()));
        }
        if (query.toExclusive() != null) {
            sql.append(" AND p.created_at < ?");
            arguments.add(Timestamp.from(query.toExclusive()));
        }
        if (query.minimumAmount() != null) {
            sql.append(" AND p.amount >= ?");
            arguments.add(query.minimumAmount());
        }
        if (query.maximumAmount() != null) {
            sql.append(" AND p.amount <= ?");
            arguments.add(query.maximumAmount());
        }
        if (query.state() != null) {
            sql.append(" AND p.status = ?");
            arguments.add(query.state().name());
        }
        if (cursorCreatedAt != null && cursorPaymentId != null) {
            sql.append(" AND (p.created_at < ? OR (p.created_at = ? AND p.id < ?))");
            arguments.add(Timestamp.from(cursorCreatedAt));
            arguments.add(Timestamp.from(cursorCreatedAt));
            arguments.add(cursorPaymentId);
        }
        sql.append(" ORDER BY p.created_at DESC, p.id DESC LIMIT ?");
        arguments.add(limit + 1);

        List<Row> rows = jdbcTemplate.query(sql.toString(), this::map, arguments.toArray());
        boolean hasMore = rows.size() > limit;
        if (hasMore) {
            rows = rows.subList(0, limit);
        }
        return new Batch(rows, hasMore);
    }

    private Row map(ResultSet resultSet, int rowNumber) throws SQLException {
        return new Row(
                resultSet.getObject("id", UUID.class),
                resultSet.getObject("tenant_id", UUID.class),
                resultSet.getObject("merchant_id", UUID.class),
                resultSet.getObject("customer_id", UUID.class),
                resultSet.getBigDecimal("amount"),
                resultSet.getString("currency"),
                PaymentStatus.valueOf(resultSet.getString("status")),
                resultSet.getTimestamp("created_at").toInstant(),
                resultSet.getTimestamp("updated_at").toInstant());
    }

    private static void appendPlaceholders(StringBuilder sql, int count) {
        for (int index = 0; index < count; index++) {
            if (index > 0) {
                sql.append(", ");
            }
            sql.append("?");
        }
    }
}
