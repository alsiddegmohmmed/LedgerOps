package com.ledgerops.reporting.infrastructure;

import com.ledgerops.payment.api.PaymentDetailsSnapshot;
import com.ledgerops.reporting.api.PaymentTimelineEntry;
import com.ledgerops.reporting.api.PaymentTimelineRebuildPort;
import com.ledgerops.reporting.api.PaymentTimelineRebuildRequest;
import com.ledgerops.reporting.api.PaymentTimelineQuery;
import com.ledgerops.reporting.api.PaymentTimelineStreamEvent;
import com.ledgerops.reporting.api.PaymentTimelineStreamQuery;
import com.ledgerops.reporting.api.PaymentTimelineStreamReplay;
import com.ledgerops.reporting.application.PaymentTimelineProjector;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

@Repository
class PaymentTimelineProjectionStore implements PaymentTimelineQuery, PaymentTimelineProjector,
        PaymentTimelineRebuildPort, PaymentTimelineStreamQuery {

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
    @Transactional
    public void ensureBaseline(PaymentDetailsSnapshot payment) {
        UUID sourceMessageId = baselineMessageId(payment.paymentId());
        PaymentTimelineEntry entry = new PaymentTimelineEntry(
                sourceMessageId,
                payment.tenantId(),
                payment.paymentId(),
                payment.merchantId(),
                "PAYMENT",
                "BASELINE_IMPORTED",
                payment.paymentId(),
                payment.createdAt(),
                "SYSTEM",
                payment.state(),
                "BASELINE_IMPORTED",
                null,
                "Current Payment state imported as the explicit Slice 3 baseline: "
                        + payment.state());
        if (insertProjection(entry, true) == 1) {
            appendStreamEvent(entry);
        }
    }

    @Override
    @Transactional
    public void project(PaymentTimelineEntry entry) {
        if (insertProjection(entry, true) == 1) {
            appendStreamEvent(entry);
        }
    }

    @Override
    @Transactional
    public void rebuild(PaymentTimelineRebuildRequest request) {
        jdbcTemplate.update("""
                DELETE FROM reporting.payment_timeline_projection
                 WHERE projection_name = ? AND tenant_id = ?
        """, PROJECTION, request.tenantId());

        for (PaymentTimelineEntry entry : request.authoritativeFacts()) {
            if (insertProjection(entry, false) == 1) {
                appendStreamEvent(entry);
            }
        }
    }

    @Override
    @Transactional(readOnly = true)
    public PaymentTimelineStreamReplay replayAfter(UUID tenantId, long lastEventId) {
        if (tenantId == null) {
            throw new NullPointerException("Tenant ID must not be null");
        }
        if (lastEventId < 0) {
            throw new IllegalArgumentException("Last event ID must not be negative");
        }

        Long earliestEventId = jdbcTemplate.queryForObject("""
                SELECT MIN(event_id)
                  FROM reporting.payment_timeline_stream_event
                 WHERE projection_name = ? AND tenant_id = ?
                """, Long.class, PROJECTION, tenantId);
        if (earliestEventId == null) {
            return lastEventId == 0
                    ? new PaymentTimelineStreamReplay(List.of(), false)
                    : PaymentTimelineStreamReplay.resync();
        }
        if (cursorUnavailable(lastEventId, earliestEventId)) {
            return PaymentTimelineStreamReplay.resync();
        }

        List<PaymentTimelineStreamEvent> events = jdbcTemplate.query("""
                SELECT event_id, source_message_id, tenant_id, payment_id, merchant_id,
                       source_module, source_type, source_id, occurred_at, actor_source,
                       outcome, reason_code, correlation_id, display_text
                  FROM reporting.payment_timeline_stream_event
                 WHERE projection_name = ? AND tenant_id = ? AND event_id > ?
                 ORDER BY event_id
                """, (rs, rowNumber) -> new PaymentTimelineStreamEvent(
                rs.getLong("event_id"), new PaymentTimelineEntry(
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
                        rs.getString("display_text"))),
                PROJECTION, tenantId, lastEventId);
        return new PaymentTimelineStreamReplay(events, false);
    }

    static boolean cursorUnavailable(long lastEventId, long earliestEventId) {
        return lastEventId > 0 && lastEventId < earliestEventId - 1;
    }

    private int insertProjection(PaymentTimelineEntry entry, boolean ignoreDuplicate) {
        String conflictClause = ignoreDuplicate
                ? "ON CONFLICT (projection_name, source_message_id) DO NOTHING"
                : "";
        return jdbcTemplate.update(("""
                INSERT INTO reporting.payment_timeline_projection (
                    projection_name, source_message_id, tenant_id, payment_id, merchant_id,
                    source_module, source_type, source_id, occurred_at, actor_source,
                    outcome, reason_code, correlation_id, display_text
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """ + conflictClause),
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

    private void appendStreamEvent(PaymentTimelineEntry entry) {
        jdbcTemplate.update("""
                INSERT INTO reporting.payment_timeline_stream_event (
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
