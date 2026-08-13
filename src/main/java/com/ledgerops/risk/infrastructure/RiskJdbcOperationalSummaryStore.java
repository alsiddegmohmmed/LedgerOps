package com.ledgerops.risk.infrastructure;

import com.ledgerops.risk.api.RiskOperationalSummaryQuery;
import com.ledgerops.risk.api.RiskReviewOperationalSummary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Risk-owned adapter for the Reporting projection rebuild boundary. */
@Repository
class RiskJdbcOperationalSummaryStore implements RiskOperationalSummaryQuery {

    private final JdbcTemplate jdbc;

    RiskJdbcOperationalSummaryStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<RiskReviewOperationalSummary> findReviewsCreated(
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
                SELECT id, tenant_id, payment_id, merchant_id, created_at
                  FROM risk.risk_reviews
                 WHERE tenant_id = ?
                   AND created_at >= ?
                   AND created_at < ?
                """);
        List<Object> arguments = new ArrayList<>(List.of(
                tenantId, Timestamp.from(fromInclusive), Timestamp.from(toExclusive)));
        if (!merchantIds.isEmpty()) {
            sql.append(" AND merchant_id IN (");
            for (int index = 0; index < merchantIds.size(); index++) {
                if (index > 0) {
                    sql.append(", ");
                }
                sql.append("?");
            }
            sql.append(")");
            arguments.addAll(merchantIds);
        }
        sql.append(" ORDER BY created_at ASC, id ASC");
        return jdbc.query(sql.toString(), (rs, row) -> new RiskReviewOperationalSummary(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("payment_id", UUID.class),
                rs.getObject("merchant_id", UUID.class),
                rs.getTimestamp("created_at").toInstant()), arguments.toArray());
    }
}
