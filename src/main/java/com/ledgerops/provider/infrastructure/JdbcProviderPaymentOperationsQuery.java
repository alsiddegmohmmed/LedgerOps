package com.ledgerops.provider.infrastructure;

import com.ledgerops.provider.api.ProviderInteractionOperation;
import com.ledgerops.provider.api.ProviderPaymentOperations;
import com.ledgerops.provider.api.ProviderPaymentOperationsQuery;
import com.ledgerops.provider.api.ProviderRecoveryOperation;
import com.ledgerops.provider.api.ProviderWebhookOperation;
import com.ledgerops.provider.api.ProviderWorkOperation;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class JdbcProviderPaymentOperationsQuery implements ProviderPaymentOperationsQuery {

    private final JdbcTemplate jdbc;

    JdbcProviderPaymentOperationsQuery(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<ProviderPaymentOperations> findByTenantAndPayment(
            UUID tenantId,
            UUID paymentId
    ) {
        List<ProviderWorkOperation> work = jdbc.query("""
                SELECT id, tenant_id, payment_id, attempt_id, attempt_sequence, work_type,
                       operation_type, operation_id,
                       status, provider_id, provider_idempotency_key, due_at, execution_count,
                       transport_retry_count, last_error_code, scenario_profile_id,
                       scenario_profile_version, created_at, updated_at
                  FROM provider.work
                 WHERE tenant_id = ? AND payment_id = ?
                 ORDER BY attempt_sequence, work_type, created_at, id
                """, this::mapWork, tenantId, paymentId);
        List<ProviderInteractionOperation> interactions = jdbc.query("""
                SELECT interaction_id, tenant_id, work_id, webhook_event_id, payment_id,
                       operation_type, operation_id,
                       attempt_id, provider_id, work_type, request_id, http_status,
                       communication_outcome, latency_millis, safe_error_code,
                       started_at, completed_at
                  FROM provider.interactions
                 WHERE tenant_id = ? AND payment_id = ?
                 ORDER BY completed_at, interaction_id
                """, this::mapInteraction, tenantId, paymentId);
        List<ProviderRecoveryOperation> recovery = jdbc.query("""
                SELECT r.evidence_id, r.work_id, r.attempt_id, r.operation_type, r.operation_id,
                       rr.retry_request_id,
                       w.status AS work_status, r.result_category, r.retry_disposition,
                       r.provider_transaction_found, r.no_acceptance_proven,
                       w.due_at, rr.requested_at, r.observed_at
                  FROM provider.results r
                  LEFT JOIN provider.work w
                    ON w.id = r.work_id AND w.tenant_id = r.tenant_id
                  LEFT JOIN provider.retry_requests rr
                    ON rr.tenant_id = r.tenant_id
                   AND rr.provider_evidence_id = r.evidence_id
                 WHERE r.tenant_id = ? AND r.payment_id = ?
                   AND (r.retry_disposition <> 'NOT_RETRYABLE'
                        OR w.status IN ('WAITING_RETRY_REQUEST', 'WAITING_STATUS', 'UNRESOLVED'))
                 ORDER BY r.observed_at, r.evidence_id
                """, this::mapRecovery, tenantId, paymentId);
        List<ProviderWebhookOperation> webhooks = jdbc.query("""
                SELECT e.event_id, e.provider_event_id, e.payment_id, e.attempt_id,
                       e.result_category, e.status,
                       (SELECT count(*) FROM provider.webhook_receipts r
                         WHERE r.tenant_id = e.tenant_id AND r.event_id = e.event_id) AS receipt_count,
                       e.received_at, e.updated_at
                  FROM provider.webhook_events e
                 WHERE e.tenant_id = ? AND e.payment_id = ?
                 ORDER BY e.received_at, e.event_id
                """, this::mapWebhook, tenantId, paymentId);
        if (work.isEmpty() && interactions.isEmpty() && recovery.isEmpty() && webhooks.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new ProviderPaymentOperations(
                tenantId, paymentId, work, interactions, recovery, webhooks));
    }

    private ProviderWorkOperation mapWork(ResultSet rs, int row) throws SQLException {
        return new ProviderWorkOperation(
                rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getObject("payment_id", UUID.class),
                com.ledgerops.provider.api.ProviderOperationType.valueOf(
                        rs.getString("operation_type")),
                rs.getObject("operation_id", UUID.class),
                rs.getObject("attempt_id", UUID.class),
                rs.getInt("attempt_sequence"), rs.getString("work_type"), rs.getString("status"),
                rs.getString("provider_id"), rs.getString("provider_idempotency_key"),
                rs.getTimestamp("due_at").toInstant(), rs.getInt("execution_count"),
                rs.getInt("transport_retry_count"), rs.getString("last_error_code"),
                rs.getObject("scenario_profile_id", UUID.class),
                nullableLong(rs, "scenario_profile_version"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }

    private ProviderInteractionOperation mapInteraction(ResultSet rs, int row) throws SQLException {
        return new ProviderInteractionOperation(
                rs.getObject("interaction_id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getObject("work_id", UUID.class), rs.getObject("webhook_event_id", UUID.class),
                rs.getObject("payment_id", UUID.class),
                com.ledgerops.provider.api.ProviderOperationType.valueOf(
                        rs.getString("operation_type")),
                rs.getObject("operation_id", UUID.class),
                rs.getObject("attempt_id", UUID.class),
                rs.getString("provider_id"), rs.getString("work_type"),
                rs.getObject("request_id", UUID.class), nullableInteger(rs, "http_status"),
                rs.getString("communication_outcome"), rs.getLong("latency_millis"),
                rs.getString("safe_error_code"), rs.getTimestamp("started_at").toInstant(),
                rs.getTimestamp("completed_at").toInstant());
    }

    private ProviderRecoveryOperation mapRecovery(ResultSet rs, int row) throws SQLException {
        return new ProviderRecoveryOperation(
                rs.getObject("evidence_id", UUID.class), rs.getObject("work_id", UUID.class),
                rs.getObject("attempt_id", UUID.class),
                com.ledgerops.provider.api.ProviderOperationType.valueOf(
                        rs.getString("operation_type")),
                rs.getObject("operation_id", UUID.class),
                rs.getObject("retry_request_id", UUID.class),
                rs.getString("work_status"), rs.getString("result_category"),
                rs.getString("retry_disposition"), rs.getBoolean("provider_transaction_found"),
                rs.getBoolean("no_acceptance_proven"), nullableInstant(rs, "due_at"),
                nullableInstant(rs, "requested_at"), rs.getTimestamp("observed_at").toInstant());
    }

    private ProviderWebhookOperation mapWebhook(ResultSet rs, int row) throws SQLException {
        return new ProviderWebhookOperation(
                rs.getObject("event_id", UUID.class), rs.getObject("provider_event_id", UUID.class),
                rs.getObject("payment_id", UUID.class), rs.getObject("attempt_id", UUID.class),
                rs.getString("result_category"), rs.getString("status"),
                rs.getLong("receipt_count"), rs.getTimestamp("received_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    private Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private Integer nullableInteger(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private java.time.Instant nullableInstant(ResultSet rs, String column) throws SQLException {
        java.sql.Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
