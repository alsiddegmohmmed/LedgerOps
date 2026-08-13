package com.ledgerops.casework.infrastructure;

import com.ledgerops.casework.api.CaseOperationalSummary;
import com.ledgerops.casework.api.CaseOperationalSummaryQuery;
import com.ledgerops.payment.api.PaymentDetailsQuery;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.Optional;

/** Casework-owned adapter for the Reporting projection rebuild boundary. */
@Repository
class CaseJdbcOperationalSummaryStore implements CaseOperationalSummaryQuery {

    private final JdbcTemplate jdbc;
    private final PaymentDetailsQuery payments;

    CaseJdbcOperationalSummaryStore(JdbcTemplate jdbc, PaymentDetailsQuery payments) {
        this.jdbc = jdbc;
        this.payments = payments;
    }

    @Override
    public List<CaseOperationalSummary> findUnresolvedCasesCreated(
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
                SELECT id, tenant_id, source_category, source_id, related_payment_id,
                       status, created_at
                  FROM casework.cases
                 WHERE tenant_id = ?
                   AND created_at >= ?
                   AND created_at < ?
                   AND status IN ('OPEN', 'INVESTIGATING', 'AWAITING_INFORMATION', 'REOPENED')
                """);
        List<Object> arguments = new ArrayList<>(List.of(
                tenantId, Timestamp.from(fromInclusive), Timestamp.from(toExclusive)));
        sql.append(" ORDER BY created_at ASC, id ASC");

        return jdbc.query(sql.toString(), (rs, row) -> {
            UUID relatedPaymentId = rs.getObject("related_payment_id", UUID.class);
            UUID merchantId = relatedPaymentId == null ? null
                    : payments.findByTenantAndPayment(tenantId, relatedPaymentId)
                    .map(value -> value.merchantId())
                    .orElse(null);
            return new CaseOperationalSummary(
                    rs.getObject("id", UUID.class),
                    rs.getObject("tenant_id", UUID.class),
                    rs.getString("source_category"),
                    rs.getObject("source_id", UUID.class),
                    relatedPaymentId,
                    merchantId,
                    rs.getString("status"),
                    rs.getTimestamp("created_at").toInstant());
        }, arguments.toArray()).stream()
                .filter(value -> merchantIds.isEmpty() || merchantIds.contains(value.merchantId()))
                .toList();
    }

    @Override
    public Optional<String> findCurrentStatusBySource(
            UUID tenantId,
            String sourceType,
            UUID sourceId
    ) {
        Objects.requireNonNull(tenantId, "Summary Tenant ID must not be null");
        Objects.requireNonNull(sourceType, "Case source type must not be null");
        Objects.requireNonNull(sourceId, "Case source ID must not be null");
        return jdbc.query("""
                SELECT status
                  FROM casework.cases
                 WHERE tenant_id = ? AND source_category = ? AND source_id = ?
                """, rs -> rs.next()
                        ? Optional.of(rs.getString("status"))
                        : Optional.empty(), tenantId, sourceType, sourceId);
    }
}
