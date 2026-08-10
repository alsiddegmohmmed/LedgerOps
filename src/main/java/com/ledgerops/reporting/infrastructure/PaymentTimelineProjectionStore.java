package com.ledgerops.reporting.infrastructure;

import com.ledgerops.payment.api.PaymentDetailsSnapshot;
import com.ledgerops.reporting.api.PaymentTimelineEntry;
import com.ledgerops.reporting.api.PaymentTimelineQuery;
import com.ledgerops.reporting.application.PaymentTimelineProjector;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

@Repository
class PaymentTimelineProjectionStore implements PaymentTimelineQuery, PaymentTimelineProjector {

    private static final String PROJECTION = "payment-timeline";
    private final JdbcTemplate jdbcTemplate;

    PaymentTimelineProjectionStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<PaymentTimelineEntry> findByTenantAndPayment(UUID tenantId, UUID paymentId) {
        return jdbcTemplate.query("""
                SELECT source_message_id, tenant_id, payment_id, merchant_id,
                       source_module, source_type, source_id, occurred_at,
                       actor_source, outcome, reason_code, correlation_id, display_text
                  FROM reporting.payment_timeline_projection
                 WHERE projection_name = ? AND tenant_id = ? AND payment_id = ?
                 ORDER BY occurred_at, source_message_id
                """, (rs, rowNumber) -> new PaymentTimelineEntry(
                rs.getObject("source_message_id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("payment_id", UUID.class),
                rs.getObject("merchant_id", UUID.class),
                rs.getString("source_module"),
                rs.getString("source_type"),
                rs.getObject("source_id", UUID.class),
                rs.getTimestamp("occurred_at").toInstant(),
                rs.getString("actor_source"),
                rs.getString("outcome"),
                rs.getString("reason_code"),
                rs.getObject("correlation_id", UUID.class),
                rs.getString("display_text")),
                PROJECTION, tenantId, paymentId);
    }

    @Override
    public void ensureBaseline(PaymentDetailsSnapshot payment) {
        UUID sourceMessageId = baselineMessageId(payment.paymentId());
        jdbcTemplate.update("""
                INSERT INTO reporting.payment_timeline_projection (
                    projection_name, source_message_id, tenant_id, payment_id, merchant_id,
                    source_module, source_type, source_id, occurred_at, actor_source,
                    outcome, reason_code, correlation_id, display_text
                ) VALUES (?, ?, ?, ?, ?, 'PAYMENT', 'BASELINE_IMPORTED', ?, ?,
                           'SYSTEM', ?, 'BASELINE_IMPORTED', NULL, ?)
                ON CONFLICT (projection_name, source_message_id) DO NOTHING
                """,
                PROJECTION,
                sourceMessageId,
                payment.tenantId(),
                payment.paymentId(),
                payment.merchantId(),
                payment.paymentId(),
                Timestamp.from(payment.createdAt()),
                payment.state(),
                "Current Payment state imported as the explicit Slice 3 baseline: "
                        + payment.state());
    }

    @Override
    public void project(PaymentTimelineEntry entry) {
        jdbcTemplate.update("""
                INSERT INTO reporting.payment_timeline_projection (
                    projection_name, source_message_id, tenant_id, payment_id, merchant_id,
                    source_module, source_type, source_id, occurred_at, actor_source,
                    outcome, reason_code, correlation_id, display_text
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (projection_name, source_message_id) DO NOTHING
                """,
                PROJECTION,
                entry.sourceMessageId(),
                entry.tenantId(),
                entry.paymentId(),
                entry.merchantId(),
                entry.sourceModule(),
                entry.sourceType(),
                entry.sourceId(),
                Timestamp.from(entry.occurredAt()),
                entry.actorSource(),
                entry.outcome(),
                entry.reasonCode(),
                entry.correlationId(),
                entry.displayText());
    }

    @Override
    public UUID baselineMessageId(UUID paymentId) {
        return UUID.nameUUIDFromBytes(
                (PROJECTION + ":baseline:" + paymentId)
                        .getBytes(StandardCharsets.UTF_8));
    }
}
