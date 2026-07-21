package com.ledgerops.messaging.infrastructure;

import com.ledgerops.messaging.api.MessageOutbox;
import com.ledgerops.messaging.api.OutboxConsistencyException;
import com.ledgerops.messaging.api.OutboxMessageDraft;
import com.ledgerops.messaging.api.ProducerName;
import com.ledgerops.messaging.api.StoredOutboxMessage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.LinkedHashMap;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
class JdbcMessageOutbox implements MessageOutbox {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String FIND_SQL = """
            SELECT id, message_id, producer_name, deduplication_key, content_hash,
                   message_type, schema_version, aggregate_id, tenant_id, topic,
                   partition_key, payload, correlation_id, causation_id, occurred_at
              FROM messaging.outbox
             WHERE producer_name = ? AND deduplication_key = ?
            """;
    private static final String INSERT_SQL = """
            INSERT INTO messaging.outbox (
                id, message_id, producer_name, deduplication_key, content_hash,
                message_type, schema_version, aggregate_id, tenant_id, topic,
                partition_key, payload, correlation_id, causation_id, occurred_at,
                status, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PENDING', ?)
            ON CONFLICT (producer_name, deduplication_key) DO NOTHING
            """;
    private static final String FIND_BY_AGGREGATE_SQL = """
            SELECT id, message_id, producer_name, deduplication_key, content_hash,
                   message_type, schema_version, aggregate_id, tenant_id, topic,
                   partition_key, payload, correlation_id, causation_id, occurred_at
              FROM messaging.outbox
             WHERE producer_name = ? AND message_type = ?
               AND tenant_id = ? AND aggregate_id = ?
            """;

    private final JdbcTemplate jdbcTemplate;

    JdbcMessageOutbox(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public StoredOutboxMessage appendOrGet(OutboxMessageDraft draft) {
        String hash = contentHash(draft);
        Optional<StoredOutboxMessage> existing = find(draft.producerName(), draft.deduplicationKey());
        if (existing.isPresent()) {
            return requireEquivalent(existing.orElseThrow(), draft, hash);
        }

        UUID outboxId = UUID.randomUUID();
        UUID messageId = UUID.randomUUID();
        Timestamp occurredAt = Timestamp.from(draft.occurredAt());
        jdbcTemplate.update(
                INSERT_SQL,
                outboxId,
                messageId,
                draft.producerName().value(),
                draft.deduplicationKey(),
                hash,
                draft.messageType(),
                draft.schemaVersion(),
                draft.aggregateId(),
                draft.tenantId(),
                draft.topic(),
                draft.partitionKey(),
                draft.canonicalPayloadJson(),
                draft.correlationId(),
                draft.causationId(),
                occurredAt,
                occurredAt
        );
        StoredOutboxMessage stored = find(
                draft.producerName(),
                draft.deduplicationKey()
        ).orElseThrow();
        return requireEquivalent(stored, draft, hash);
    }

    @Override
    public Optional<StoredOutboxMessage> find(
            ProducerName producerName,
            String deduplicationKey
    ) {
        return jdbcTemplate.query(
                FIND_SQL,
                this::map,
                producerName.value(),
                deduplicationKey
        ).stream().findFirst();
    }

    @Override
    public Optional<StoredOutboxMessage> findByAggregate(
            ProducerName producerName,
            String messageType,
            UUID tenantId,
            UUID aggregateId
    ) {
        return jdbcTemplate.query(
                FIND_BY_AGGREGATE_SQL,
                this::map,
                producerName.value(),
                messageType,
                tenantId,
                aggregateId
        ).stream().findFirst();
    }

    @Override
    public StoredOutboxMessage requireExistingEquivalent(OutboxMessageDraft draft) {
        StoredOutboxMessage existing = find(
                draft.producerName(),
                draft.deduplicationKey()
        ).orElseThrow(() -> new OutboxConsistencyException(
                "Required outbox record does not exist"
        ));
        return requireEquivalent(existing, draft, contentHash(draft));
    }

    private StoredOutboxMessage requireEquivalent(
            StoredOutboxMessage existing,
            OutboxMessageDraft draft,
            String expectedHash
    ) {
        if (!existing.contentHash().equals(expectedHash)) {
            throw new OutboxConsistencyException(
                    "Outbox business identity was reused with different content"
            );
        }
        if (!existing.producerName().equals(draft.producerName())
                || !existing.deduplicationKey().equals(draft.deduplicationKey())
                || !existing.messageType().equals(draft.messageType())
                || existing.schemaVersion() != draft.schemaVersion()
                || !existing.aggregateId().equals(draft.aggregateId())
                || !existing.tenantId().equals(draft.tenantId())
                || !existing.topic().equals(draft.topic())
                || !existing.partitionKey().equals(draft.partitionKey())
                || !parsePayload(existing.canonicalPayloadJson()).equals(
                        parsePayload(draft.canonicalPayloadJson())
                )) {
            throw new OutboxConsistencyException(
                    "Stored outbox business content does not match its identity"
            );
        }
        return existing;
    }

    String contentHash(OutboxMessageDraft draft) {
        JsonNode payload = parsePayload(draft.canonicalPayloadJson());
        LinkedHashMap<String, Object> content = new LinkedHashMap<>();
        content.put("messageType", draft.messageType());
        content.put("schemaVersion", draft.schemaVersion());
        content.put("aggregateId", draft.aggregateId().toString());
        content.put("tenantId", draft.tenantId().toString());
        content.put("topic", draft.topic());
        content.put("partitionKey", draft.partitionKey());
        content.put("payload", payload);
        String canonical;
        try {
            canonical = JSON.writeValueAsString(content);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Cannot serialize outbox business content", exception);
        }
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(canonical.getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private JsonNode parsePayload(String payload) {
        try {
            JsonNode parsed = JSON.readTree(payload);
            if (parsed == null || !parsed.isObject()) {
                throw new IllegalArgumentException("Canonical payload must be a JSON object");
            }
            if (!JSON.writeValueAsString(parsed).equals(payload)) {
                throw new IllegalArgumentException(
                        "Payload must use the canonical JSON byte representation"
                );
            }
            return parsed;
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("Canonical payload is not valid JSON", exception);
        }
    }

    private StoredOutboxMessage map(ResultSet rs, int rowNumber) throws SQLException {
        return new StoredOutboxMessage(
                rs.getObject("id", UUID.class),
                rs.getObject("message_id", UUID.class),
                ProducerName.valueOf(rs.getString("producer_name").toUpperCase()),
                rs.getString("deduplication_key"),
                rs.getString("content_hash"),
                rs.getString("message_type"),
                rs.getInt("schema_version"),
                rs.getObject("aggregate_id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getString("topic"),
                rs.getString("partition_key"),
                rs.getString("payload"),
                rs.getObject("correlation_id", UUID.class),
                rs.getObject("causation_id", UUID.class),
                rs.getTimestamp("occurred_at").toInstant()
        );
    }

}
