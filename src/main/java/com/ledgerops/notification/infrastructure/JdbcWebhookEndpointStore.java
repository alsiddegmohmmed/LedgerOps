package com.ledgerops.notification.infrastructure;

import com.ledgerops.notification.api.WebhookDelivery;
import com.ledgerops.notification.api.WebhookEndpoint;
import com.ledgerops.notification.api.WebhookEndpointPort;
import com.ledgerops.notification.api.WebhookEndpointStatus;
import com.ledgerops.notification.api.WebhookEventType;
import com.ledgerops.notification.api.WebhookSecretResult;
import com.ledgerops.notification.application.WebhookEndpointNotFoundException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Repository
@ConditionalOnProperty(name = "ledgerops.notification.enabled", havingValue = "true")
class JdbcWebhookEndpointStore implements WebhookEndpointPort {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final JdbcTemplate jdbc;
    private final WebhookSecretCipher cipher;
    private final WebhookUrlPolicy urlPolicy;

    JdbcWebhookEndpointStore(
            JdbcTemplate jdbc,
            WebhookSecretCipher cipher,
            WebhookUrlPolicy urlPolicy
    ) {
        this.jdbc = jdbc;
        this.cipher = cipher;
        this.urlPolicy = urlPolicy;
    }

    @Override
    @Transactional
    public WebhookSecretResult create(
            UUID tenantId,
            UUID merchantId,
            String label,
            String endpointUrl,
            Set<WebhookEventType> allowedEventTypes,
            Instant now
    ) {
        if (label == null || label.isBlank() || label.length() > 120) {
            throw new IllegalArgumentException("Webhook label must contain 1 to 120 characters");
        }
        URI validated = urlPolicy.validate(endpointUrl);
        if (allowedEventTypes == null || allowedEventTypes.isEmpty()) {
            throw new IllegalArgumentException("At least one webhook event type is required");
        }
        String secret = cipher.generatePlaintextSecret();
        WebhookSecretCipher.EncryptedSecret encrypted = cipher.encrypt(secret);
        UUID endpointId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO notification.webhook_endpoints
                    (endpoint_id, tenant_id, merchant_id, label, endpoint_url, status,
                     encrypted_secret, secret_nonce, key_version, allowed_event_types,
                     created_at, rotated_at)
                VALUES (?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?, ?, ?, ?)
                """, endpointId, tenantId, merchantId, label, validated.toString(),
                encrypted.ciphertext(), encrypted.nonce(), encrypted.keyVersion(),
                eventValues(allowedEventTypes), Timestamp.from(now), Timestamp.from(now));
        return new WebhookSecretResult(findEndpoint(tenantId, merchantId, endpointId), secret);
    }

    @Override
    @Transactional
    public WebhookSecretResult rotate(UUID tenantId, UUID merchantId, UUID endpointId, Instant now) {
        StoredEndpoint current = findStoredEndpoint(tenantId, merchantId, endpointId, true);
        if (!WebhookEndpointStatus.ACTIVE.name().equals(current.endpointStatus())) {
            throw new IllegalStateException("A revoked webhook endpoint cannot be rotated");
        }
        String secret = cipher.generatePlaintextSecret();
        WebhookSecretCipher.EncryptedSecret encrypted = cipher.encrypt(secret);
        jdbc.update("""
                UPDATE notification.webhook_endpoints
                   SET encrypted_secret = ?, secret_nonce = ?, key_version = ?, rotated_at = ?
                 WHERE endpoint_id = ? AND tenant_id = ? AND merchant_id = ?
                """, encrypted.ciphertext(), encrypted.nonce(), encrypted.keyVersion(),
                Timestamp.from(now), endpointId, tenantId, merchantId);
        return new WebhookSecretResult(findEndpoint(tenantId, merchantId, endpointId), secret);
    }

    @Override
    @Transactional
    public WebhookEndpoint revoke(UUID tenantId, UUID merchantId, UUID endpointId, Instant now) {
        findStoredEndpoint(tenantId, merchantId, endpointId, true);
        int changed = jdbc.update("""
                UPDATE notification.webhook_endpoints
                   SET status = 'REVOKED', revoked_at = COALESCE(revoked_at, ?)
                 WHERE endpoint_id = ? AND tenant_id = ? AND merchant_id = ?
                """, Timestamp.from(now), endpointId, tenantId, merchantId);
        if (changed != 1) throw new WebhookEndpointNotFoundException();
        jdbc.update("""
                UPDATE notification.webhook_deliveries
                   SET status = 'CANCELLED', lease_owner = NULL, lease_token = NULL,
                       lease_expires_at = NULL, updated_at = ?
                 WHERE endpoint_id = ? AND status IN ('PENDING', 'RETRYABLE')
                """, Timestamp.from(now), endpointId);
        return findEndpoint(tenantId, merchantId, endpointId);
    }

    @Override
    public List<WebhookEndpoint> list(UUID tenantId, UUID merchantId) {
        return jdbc.query("""
                SELECT endpoint_id, tenant_id, merchant_id, label, endpoint_url, status,
                       key_version, allowed_event_types, created_at, rotated_at, revoked_at
                  FROM notification.webhook_endpoints
                 WHERE tenant_id = ? AND merchant_id = ?
                 ORDER BY created_at DESC, endpoint_id DESC
                """, this::mapEndpoint, tenantId, merchantId);
    }

    @Override
    @Transactional
    public WebhookDelivery trigger(
            UUID tenantId,
            UUID merchantId,
            UUID endpointId,
            WebhookEventType eventType,
            Map<String, Object> payload,
            Instant now
    ) {
        StoredEndpoint endpoint = findStoredEndpoint(tenantId, merchantId, endpointId, true);
        if (!WebhookEndpointStatus.ACTIVE.name().equals(endpoint.endpointStatus())) {
            throw new IllegalStateException("A revoked webhook endpoint cannot receive new events");
        }
        if (!endpoint.allowedEventTypes().contains(eventType)) {
            throw new IllegalArgumentException("The event type is not enabled for this endpoint");
        }
        String body = jsonPayload(tenantId, merchantId, eventType, payload);
        UUID eventId = UUID.randomUUID();
        UUID deliveryId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO notification.webhook_events
                    (event_id, tenant_id, merchant_id, endpoint_id, event_type, payload, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, eventId, tenantId, merchantId, endpointId, eventType.value(), body,
                Timestamp.from(now));
        jdbc.update("""
                INSERT INTO notification.webhook_deliveries
                    (delivery_id, event_id, tenant_id, merchant_id, endpoint_id, status,
                     attempt_count, next_attempt_at, encrypted_secret, secret_nonce, key_version,
                     created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'PENDING', 0, ?, ?, ?, ?, ?, ?)
                """, deliveryId, eventId, tenantId, merchantId, endpointId, Timestamp.from(now),
                endpoint.encryptedSecret(), endpoint.secretNonce(), endpoint.keyVersion(),
                Timestamp.from(now), Timestamp.from(now));
        return findDelivery(tenantId, merchantId, endpointId, deliveryId);
    }

    @Override
    public List<WebhookDelivery> deliveries(UUID tenantId, UUID merchantId, UUID endpointId) {
        if (findEndpoint(tenantId, merchantId, endpointId) == null) {
            throw new WebhookEndpointNotFoundException();
        }
        return jdbc.query("""
                SELECT d.delivery_id, d.event_id, d.tenant_id, d.merchant_id, d.endpoint_id,
                       e.status AS endpoint_status, e.event_type, d.status, d.attempt_count,
                       d.next_attempt_at, d.created_at, d.updated_at, d.last_http_status,
                       d.last_outcome, d.last_safe_summary
                  FROM notification.webhook_deliveries d
                  JOIN notification.webhook_events w ON w.event_id = d.event_id
                  JOIN notification.webhook_endpoints e ON e.endpoint_id = d.endpoint_id
                 WHERE d.tenant_id = ? AND d.merchant_id = ? AND d.endpoint_id = ?
                 ORDER BY d.created_at DESC, d.delivery_id DESC
                """, this::mapDelivery, tenantId, merchantId, endpointId);
    }

    StoredEndpoint claim(UUID deliveryId, String leaseOwner, UUID leaseToken, Instant now) {
        Instant expires = now.plusSeconds(30);
        return jdbc.query("""
                WITH candidate AS (
                    SELECT d.delivery_id
                      FROM notification.webhook_deliveries d
                      JOIN notification.webhook_endpoints e ON e.endpoint_id = d.endpoint_id
                     WHERE (d.status IN ('PENDING', 'RETRYABLE') AND d.next_attempt_at <= ?
                            OR d.status = 'CLAIMED' AND d.lease_expires_at <= ?)
                       AND e.status = 'ACTIVE'
                     ORDER BY d.next_attempt_at, d.created_at, d.delivery_id
                     FOR UPDATE SKIP LOCKED
                     LIMIT 1
                ), updated AS (
                    UPDATE notification.webhook_deliveries d
                       SET status = 'CLAIMED', lease_owner = ?, lease_token = ?,
                           lease_expires_at = ?, updated_at = ?
                      FROM candidate
                     WHERE d.delivery_id = candidate.delivery_id
                    RETURNING d.*
                )
                SELECT u.*, e.endpoint_url, e.status AS endpoint_status, w.event_type, w.payload
                  FROM updated u
                  JOIN notification.webhook_endpoints e ON e.endpoint_id = u.endpoint_id
                  JOIN notification.webhook_events w ON w.event_id = u.event_id
                """, (rs, row) -> rs.next() ? mapClaim(rs, leaseToken, expires, now) : null,
                Timestamp.from(now), Timestamp.from(now), leaseOwner, leaseToken,
                Timestamp.from(expires), Timestamp.from(now)).stream().findFirst().orElse(null);
    }

    @Transactional
    void complete(
            StoredEndpoint claim,
            Instant now,
            int httpStatus,
            String outcome,
            String safeSummary,
            String responseBodyHash,
            boolean retryable
    ) {
        int attemptNumber = claim.attemptCount() + 1;
        boolean revoked = !"ACTIVE".equals(claim.endpointStatus());
        boolean retry = retryable && !revoked && attemptNumber < 5;
        String status = revoked ? "CANCELLED" : retry ? "RETRYABLE" :
                (httpStatus >= 200 && httpStatus < 300 ? "DELIVERED" : "FAILED");
        Instant nextAttempt = retry ? now.plus(WebhookRetrySchedule.delay(
                claim.deliveryId(), attemptNumber)) : now;
        jdbc.update("""
                INSERT INTO notification.webhook_attempts
                    (attempt_id, delivery_id, attempt_number, started_at, completed_at,
                     http_status, outcome, response_body_hash, safe_summary)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), claim.deliveryId(), attemptNumber,
                Timestamp.from(claim.startedAt()), Timestamp.from(now),
                httpStatus == 0 ? null : httpStatus, outcome, responseBodyHash, safeSummary);
        int updated = jdbc.update("""
                UPDATE notification.webhook_deliveries
                   SET status = ?, attempt_count = ?, next_attempt_at = ?,
                       lease_owner = NULL, lease_token = NULL, lease_expires_at = NULL,
                       updated_at = ?, last_http_status = ?, last_outcome = ?, last_safe_summary = ?
                 WHERE delivery_id = ? AND status = 'CLAIMED' AND lease_token = ?
                """, status, attemptNumber, Timestamp.from(nextAttempt), Timestamp.from(now),
                httpStatus == 0 ? null : httpStatus, outcome, safeSummary,
                claim.deliveryId(), claim.leaseToken());
        if (updated != 1) throw new IllegalStateException("Webhook delivery lease was lost");
    }

    boolean endpointIsActive(UUID endpointId) {
        Boolean active = jdbc.queryForObject(
                "SELECT status = 'ACTIVE' FROM notification.webhook_endpoints WHERE endpoint_id = ?",
                Boolean.class, endpointId);
        return Boolean.TRUE.equals(active);
    }

    @Transactional
    void cancel(StoredEndpoint claim, Instant now, String reason) {
        jdbc.update("""
                UPDATE notification.webhook_deliveries
                   SET status = 'CANCELLED', lease_owner = NULL, lease_token = NULL,
                       lease_expires_at = NULL, updated_at = ?, last_outcome = ?,
                       last_safe_summary = ?
                 WHERE delivery_id = ? AND status = 'CLAIMED' AND lease_token = ?
                """, Timestamp.from(now), reason, reason, claim.deliveryId(), claim.leaseToken());
    }

    private WebhookEndpoint findEndpoint(UUID tenantId, UUID merchantId, UUID endpointId) {
        return jdbc.query("""
                SELECT endpoint_id, tenant_id, merchant_id, label, endpoint_url, status,
                       key_version, allowed_event_types, created_at, rotated_at, revoked_at
                  FROM notification.webhook_endpoints
                 WHERE endpoint_id = ? AND tenant_id = ? AND merchant_id = ?
                """, rs -> rs.next() ? mapEndpoint(rs, 0) : null,
                endpointId, tenantId, merchantId);
    }

    private StoredEndpoint findStoredEndpoint(
            UUID tenantId, UUID merchantId, UUID endpointId, boolean forUpdate
    ) {
        String suffix = forUpdate ? " FOR UPDATE" : "";
        StoredEndpoint result = jdbc.query("""
                SELECT endpoint_id, tenant_id, merchant_id, label, endpoint_url, status,
                       encrypted_secret, secret_nonce, key_version, allowed_event_types,
                       created_at, rotated_at, revoked_at
                  FROM notification.webhook_endpoints
                 WHERE endpoint_id = ? AND tenant_id = ? AND merchant_id = ?
                """ + suffix, (rs, row) -> rs.next() ? mapEndpointStorage(rs) : null,
                endpointId, tenantId, merchantId).stream().findFirst().orElse(null);
        if (result == null) throw new WebhookEndpointNotFoundException();
        return result;
    }

    private WebhookDelivery findDelivery(
            UUID tenantId, UUID merchantId, UUID endpointId, UUID deliveryId
    ) {
        return jdbc.query("""
                SELECT d.delivery_id, d.event_id, d.tenant_id, d.merchant_id, d.endpoint_id,
                       e.status AS endpoint_status, w.event_type, d.status, d.attempt_count,
                       d.next_attempt_at, d.created_at, d.updated_at, d.last_http_status,
                       d.last_outcome, d.last_safe_summary
                  FROM notification.webhook_deliveries d
                  JOIN notification.webhook_events w ON w.event_id = d.event_id
                  JOIN notification.webhook_endpoints e ON e.endpoint_id = d.endpoint_id
                 WHERE d.delivery_id = ? AND d.tenant_id = ? AND d.merchant_id = ?
                   AND d.endpoint_id = ?
                """, rs -> rs.next() ? mapDelivery(rs, 0) : null,
                deliveryId, tenantId, merchantId, endpointId);
    }

    private WebhookEndpoint mapEndpoint(ResultSet rs, int row) throws SQLException {
        return new WebhookEndpoint(
                rs.getObject("endpoint_id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getObject("merchant_id", UUID.class), rs.getString("label"),
                rs.getString("endpoint_url"), WebhookEndpointStatus.valueOf(rs.getString("status")),
                rs.getString("key_version"), eventTypes(rs.getArray("allowed_event_types")),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("rotated_at").toInstant(),
                rs.getTimestamp("revoked_at") == null ? null : rs.getTimestamp("revoked_at").toInstant());
    }

    private StoredEndpoint mapEndpointStorage(ResultSet rs) throws SQLException {
        WebhookEndpoint endpoint = mapEndpoint(rs, 0);
        return new StoredEndpoint(
                endpoint.endpointId(), endpoint.tenantId(), endpoint.merchantId(), endpoint.endpointUrl(),
                endpoint.status().name(), rs.getBytes("encrypted_secret"), rs.getBytes("secret_nonce"),
                endpoint.keyVersion(), endpoint.allowedEventTypes(), 0,
                null, null, null, null, null, null, null);
    }

    private StoredEndpoint mapClaim(
            ResultSet rs, UUID leaseToken, Instant leaseExpires, Instant startedAt
    ) throws SQLException {
        return new StoredEndpoint(
                rs.getObject("endpoint_id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("merchant_id", UUID.class),
                rs.getString("endpoint_url"),
                rs.getString("endpoint_status"),
                rs.getBytes("encrypted_secret"),
                rs.getBytes("secret_nonce"),
                rs.getString("key_version"),
                Set.of(),
                rs.getInt("attempt_count"),
                leaseToken,
                leaseExpires,
                rs.getObject("delivery_id", UUID.class),
                rs.getObject("event_id", UUID.class),
                rs.getString("event_type"),
                rs.getString("payload"),
                startedAt);
    }

    private WebhookDelivery mapDelivery(ResultSet rs, int row) throws SQLException {
        return new WebhookDelivery(
                rs.getObject("delivery_id", UUID.class), rs.getObject("event_id", UUID.class),
                rs.getObject("tenant_id", UUID.class), rs.getObject("merchant_id", UUID.class),
                rs.getObject("endpoint_id", UUID.class),
                WebhookEndpointStatus.valueOf(rs.getString("endpoint_status")),
                rs.getString("event_type"), rs.getString("status"), rs.getInt("attempt_count"),
                rs.getTimestamp("next_attempt_at").toInstant(), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                (Integer) rs.getObject("last_http_status"), rs.getString("last_outcome"),
                rs.getString("last_safe_summary"));
    }

    private Set<WebhookEventType> eventTypes(java.sql.Array sqlArray) throws SQLException {
        Object[] values = (Object[]) sqlArray.getArray();
        EnumSet<WebhookEventType> types = EnumSet.noneOf(WebhookEventType.class);
        for (Object value : values) types.add(WebhookEventType.fromValue(value.toString()));
        return types;
    }

    private String[] eventValues(Set<WebhookEventType> types) {
        return types.stream().map(WebhookEventType::value).sorted().toArray(String[]::new);
    }

    private String jsonPayload(
            UUID tenantId, UUID merchantId, WebhookEventType type, Map<String, Object> supplied
    ) {
        LinkedHashMap<String, Object> payload = new LinkedHashMap<>();
        payload.put("schemaVersion", 1);
        payload.put("eventType", type.value());
        payload.put("tenantId", tenantId);
        payload.put("merchantId", merchantId);
        if (supplied != null) payload.putAll(supplied);
        try {
            return JSON.writeValueAsString(payload);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Webhook payload could not be encoded", exception);
        }
    }

    record StoredEndpoint(
            UUID endpointId,
            UUID tenantId,
            UUID merchantId,
            String endpointUrl,
            String endpointStatus,
            byte[] encryptedSecret,
            byte[] secretNonce,
            String keyVersion,
            Set<WebhookEventType> allowedEventTypes,
            int attemptCount,
            UUID leaseToken,
            Instant leaseExpiresAt,
            UUID deliveryId,
            UUID eventId,
            String eventType,
            String payload,
            Instant startedAt
    ) {
        StoredEndpoint {
            encryptedSecret = encryptedSecret.clone();
            secretNonce = secretNonce.clone();
        }
    }
}
