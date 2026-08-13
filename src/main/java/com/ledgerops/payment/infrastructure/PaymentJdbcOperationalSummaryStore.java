package com.ledgerops.payment.infrastructure;

import com.ledgerops.payment.api.PaymentOperationalSummaryOutcome;
import com.ledgerops.payment.api.PaymentOperationalSummaryPayment;
import com.ledgerops.payment.api.PaymentOperationalSummaryQuery;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Payment-owned adapter for the Reporting projection rebuild boundary. */
@Repository
class PaymentJdbcOperationalSummaryStore implements PaymentOperationalSummaryQuery {

    private final JdbcTemplate jdbc;

    PaymentJdbcOperationalSummaryStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<PaymentOperationalSummaryPayment> findPayments(
            UUID tenantId,
            Instant fromInclusive,
            Instant toExclusive,
            Set<UUID> merchantIds
    ) {
        Period period = period(tenantId, fromInclusive, toExclusive, merchantIds);
        StringBuilder sql = new StringBuilder("""
                SELECT id, tenant_id, merchant_id, amount, currency, created_at
                  FROM payment.payments
                 WHERE tenant_id = ?
                   AND created_at >= ?
                   AND created_at < ?
                """);
        List<Object> arguments = new ArrayList<>(List.of(
                period.tenantId(),
                Timestamp.from(period.fromInclusive()),
                Timestamp.from(period.toExclusive())));
        appendMerchantFilter(sql, arguments, period.merchantIds());
        sql.append(" ORDER BY created_at ASC, id ASC");
        return jdbc.query(sql.toString(), (rs, row) -> new PaymentOperationalSummaryPayment(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("merchant_id", UUID.class),
                rs.getBigDecimal("amount"),
                rs.getString("currency"),
                rs.getTimestamp("created_at").toInstant()), arguments.toArray());
    }

    @Override
    public List<PaymentOperationalSummaryOutcome> findDefinitiveProviderOutcomes(
            UUID tenantId,
            Instant fromInclusive,
            Instant toExclusive,
            Set<UUID> merchantIds
    ) {
        Period period = period(tenantId, fromInclusive, toExclusive, merchantIds);
        StringBuilder sql = new StringBuilder("""
                SELECT f.payment_id, f.tenant_id, p.merchant_id,
                       f.final_category, f.applied_at
                  FROM payment.accepted_final_provider_results f
                  JOIN payment.payments p
                    ON p.tenant_id = f.tenant_id AND p.id = f.payment_id
                 WHERE f.tenant_id = ?
                   AND f.applied_at >= ?
                   AND f.applied_at < ?
                """);
        List<Object> arguments = new ArrayList<>(List.of(
                period.tenantId(),
                Timestamp.from(period.fromInclusive()),
                Timestamp.from(period.toExclusive())));
        appendMerchantFilter(sql, arguments, period.merchantIds(), "p.merchant_id");
        sql.append(" ORDER BY f.applied_at ASC, f.payment_id ASC");
        return jdbc.query(sql.toString(), (rs, row) -> new PaymentOperationalSummaryOutcome(
                rs.getObject("payment_id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("merchant_id", UUID.class),
                rs.getString("final_category"),
                rs.getTimestamp("applied_at").toInstant()), arguments.toArray());
    }

    private static Period period(
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
        return new Period(tenantId, fromInclusive, toExclusive, Set.copyOf(merchantIds));
    }

    private static void appendMerchantFilter(
            StringBuilder sql,
            List<Object> arguments,
            Set<UUID> merchantIds
    ) {
        appendMerchantFilter(sql, arguments, merchantIds, "merchant_id");
    }

    private static void appendMerchantFilter(
            StringBuilder sql,
            List<Object> arguments,
            Set<UUID> merchantIds,
            String column
    ) {
        if (merchantIds.isEmpty()) {
            return;
        }
        sql.append(" AND ").append(column).append(" IN (");
        for (int index = 0; index < merchantIds.size(); index++) {
            if (index > 0) {
                sql.append(", ");
            }
            sql.append("?");
        }
        sql.append(")");
        arguments.addAll(merchantIds);
    }

    private record Period(
            UUID tenantId,
            Instant fromInclusive,
            Instant toExclusive,
            Set<UUID> merchantIds
    ) {
    }
}
